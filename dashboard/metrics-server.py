#!/usr/bin/env python3
"""
Metrics server for the Knative Fluss Broker data flow dashboard.
Fetches real metrics from Flink, Fluss, and LocalStack, serves as JSON API.
Run: python3 dashboard/metrics-server.py
Dashboard: http://localhost:9090/dashboard/data-flow-dashboard.html
"""
import json
import subprocess
import sys
import urllib.request
from http.server import HTTPServer, SimpleHTTPRequestHandler
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "dashboard"


def fetch_json(url, timeout=5):
    """Fetch JSON from a URL, return None on failure."""
    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read())
    except Exception:
        return None


def get_flink_jobs():
    """Get Flink job overview from REST API."""
    data = fetch_json("http://localhost:8081/jobs/overview")
    if not data or "jobs" not in data:
        return []
    return [
        {
            "id": j["jid"][:8],
            "name": j["name"],
            "state": j["state"],
            "durationMs": j["duration"],
            "tasks": j.get("tasks", {}),
        }
        for j in data["jobs"]
    ]


def get_flink_job_metrics(job_id):
    """Get metrics for a specific Flink job."""
    data = fetch_json(
        f"http://localhost:8081/jobs/{job_id}/metrics?get=numBytesInPerSecond,numBytesOutPerSecond,numRecordsInPerSecond,numRecordsOutPerSecond"
    )
    if not data:
        return {}
    return {m["id"]: m.get("value", 0) for m in data}


def get_docker_stats():
    """Get container stats via docker CLI."""
    try:
        result = subprocess.run(
            ["docker", "ps", "--format", "{{.Names}}|{{.Status}}"],
            capture_output=True,
            text=True,
            timeout=5,
        )
        containers = []
        for line in result.stdout.strip().split("\n"):
            if "|" in line:
                name, status = line.split("|", 1)
                containers.append({"name": name, "status": status})
        return containers
    except Exception:
        return []


def get_s3_files():
    """Count objects in LocalStack S3 bucket."""
    try:
        result = subprocess.run(
            [
                "docker",
                "exec",
                "fluss-localstack",
                "awslocal",
                "s3",
                "ls",
                "s3://iceberg-warehouse/",
                "--recursive",
            ],
            capture_output=True,
            text=True,
            timeout=5,
        )
        lines = [l for l in result.stdout.strip().split("\n") if l.strip()]
        total_bytes = 0
        for line in lines:
            parts = line.split()
            if len(parts) >= 3:
                try:
                    total_bytes += int(parts[2])
                except ValueError:
                    pass
        return {"files": len(lines), "totalBytes": total_bytes}
    except Exception:
        return {"files": 0, "totalBytes": 0}


def gather_metrics():
    """Gather all metrics from live systems."""
    jobs = get_flink_jobs()

    # Find tiering job
    tiering_job = next(
        (j for j in jobs if "Tiering" in j.get("name", "")), None
    )
    insert_jobs = [j for j in jobs if "insert" in j.get("name", "").lower()]

    # Get metrics for tiering job
    tiering_metrics = {}
    if tiering_job:
        tiering_metrics = get_flink_job_metrics(tiering_job["id"])

    s3_info = get_s3_files()
    containers = get_docker_stats()

    # Count running containers
    running = len([c for c in containers if "Up" in c.get("status", "")])

    return {
        "timestamp": __import__("datetime").datetime.utcnow().isoformat() + "Z",
        "flink": {
            "jobs": jobs,
            "tieringJob": tiering_job,
            "tieringMetrics": tiering_metrics,
            "insertJobCount": len(insert_jobs),
        },
        "s3": s3_info,
        "containers": {"total": len(containers), "running": running},
        "clusterHealthy": running >= 5,
    }


class MetricsHandler(SimpleHTTPRequestHandler):
    """HTTP handler that serves both static files and /api/metrics."""

    def do_GET(self):
        if self.path == "/api/metrics":
            data = gather_metrics()
            body = json.dumps(data, indent=2).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            # Serve static files from dashboard directory
            if self.path == "/":
                self.path = "/data-flow-dashboard.html"
            super().do_GET()

    def log_message(self, format, *args):
        pass  # Suppress request logging


def main():
    port = 9090
    # Change to dashboard dir for static file serving
    import os
    os.chdir(str(ROOT.parent))
    server = HTTPServer(("0.0.0.0", port), MetricsHandler)
    print(f"Metrics server running at http://localhost:{port}")
    print(f"  Dashboard: http://localhost:{port}/dashboard/data-flow-dashboard.html")
    print(f"  API:       http://localhost:{port}/api/metrics")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down.")


if __name__ == "__main__":
    main()
