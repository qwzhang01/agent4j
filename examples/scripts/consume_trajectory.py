#!/usr/bin/env python3
"""Stage 14 trajectory consumer proof (java-agent-framework / agent-trace-export).

Reads a trajectories.jsonl file, verifies the v1 envelope, prints statistics
and materializes one SFT-style sample. Python3 standard library only - this
script is the executable proof that exported trajectories are consumable by
the training side (Mini VERL line) without any Java on the consumer machine.

Usage:
    python3 consume_trajectory.py [trajectories.jsonl]
"""

import json
import sys
from collections import Counter


def fail(msg):
    print(f"CONSUMER ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def verify_envelope(index, record):
    if not isinstance(record, dict):
        fail(f"line {index}: not a JSON object")
    if record.get("api_version") != "v1":
        fail(f"line {index}: api_version != 'v1' (got {record.get('api_version')!r})")
    if record.get("kind") != "Trajectory":
        fail(f"line {index}: kind != 'Trajectory' (got {record.get('kind')!r})")
    for field in ("trajectory_id", "run_id", "status", "messages", "steps"):
        if field not in record:
            fail(f"line {index}: missing required field '{field}'")


def main(path):
    statuses = Counter()
    tool_calls = Counter()
    rewards = []
    total_steps = 0
    prompt_tokens = 0
    completion_tokens = 0
    total_tokens = 0
    trajectories = []

    with open(path, "r", encoding="utf-8") as handle:
        for index, line in enumerate(handle, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as error:
                fail(f"line {index}: invalid JSON - {error}")
            verify_envelope(index, record)
            trajectories.append(record)

            statuses[record["status"]] += 1
            if record.get("reward") is not None:
                rewards.append(record["reward"])
            total_steps += len(record["steps"])
            for step in record["steps"]:
                for observation in step.get("observations", []):
                    tool_calls[observation.get("name", "<unnamed>")] += 1
            usage = record.get("metadata", {}).get("token_usage", {})
            prompt_tokens += usage.get("prompt_tokens", 0)
            completion_tokens += usage.get("completion_tokens", 0)
            total_tokens += usage.get("total_tokens", 0)

    if not trajectories:
        fail(f"{path}: no trajectories found")

    print("=" * 62)
    print(f"file              : {path}")
    print(f"trajectories      : {len(trajectories)}")
    print(f"status counts     : {dict(statuses)}")
    print(f"model calls total : {total_steps}")
    if rewards:
        average = sum(rewards) / len(rewards)
        print(f"avg reward        : {average:.4f}  (n={len(rewards)})")
    if tool_calls:
        print(f"tool call dist    : {dict(tool_calls)}")
    print(f"prompt tokens     : {prompt_tokens}")
    print(f"completion tokens : {completion_tokens}")
    print(f"total tokens      : {total_tokens}")
    print("=" * 62)

    # Materialize one SFT-style sample from the first trajectory: the full
    # logical conversation is exactly what supervised fine-tuning consumes.
    sample = trajectories[0]["messages"]
    print("SFT sample (first trajectory, messages):")
    print(json.dumps(sample, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else "trajectories.jsonl"
    sys.exit(main(target))
