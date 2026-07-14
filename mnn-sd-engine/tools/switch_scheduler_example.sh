#!/bin/bash
# Example shell tool for switching the SD scheduler in mnn-sd-engine.
# This is a template for future CLI wiring from the C API.

set -e

SCHEDULER=${1:-dpm_pp_2m_karras}
shift || true

echo "=== nezumi-ai SD Engine Scheduler Switcher ==="
echo "Selected scheduler: $SCHEDULER"
echo "Supported values: euler, ddim, dpm, dpm_pp_2m, dpm_pp_2m_karras, lcm, euler_a, unipc"
echo "Remaining args: $*"

echo ""
echo "When the CLI is wired up, map this string to the MnnSdScheduler enum and pass it via GenerateParams."
