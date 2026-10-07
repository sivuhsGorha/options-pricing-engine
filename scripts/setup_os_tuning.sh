#!/bin/bash
# ==============================================================================
# HFT Bare-Metal Server Tuning Script (Phase 5.1 & 5.3)
# ==============================================================================

echo "Applying Institutional OS Tuning..."

# 1. CPU Power Governor to Performance (Disable sleep states)
echo "performance" | sudo tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor

# 2. IRQ Affinity Tuning
# Push hardware interrupts to core 0 and 1, leaving cores 2-15 completely free 
# for our Java trading threads.
# Note: Requires GRUB update: GRUB_CMDLINE_LINUX_DEFAULT="isolcpus=2-15 nohz_full=2-15 rcu_nocbs=2-15"
# systemctl stop irqbalance
# echo 3 > /proc/irq/eth0/smp_affinity

# 3. Solarflare OpenOnload (Kernel Bypass)
# Bypasses the standard Linux TCP/IP stack to talk directly to the NIC via user-space.
# Usage:
# onload --profile=latency java -XX:+UseZGC -XX:+AlwaysPreTouch -jar target/options-pricer.jar
echo "Governor set (if sudo succeeded). IRQ affinity and OpenOnload above are commented examples only;"
echo "this script does not install or verify them."
