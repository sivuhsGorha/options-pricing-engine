# Enterprise Infrastructure & Low-Latency JVM Tuning (INFRA.md)

> **Status: deployment ideas, not verified.** Nothing here is installed or measured by this repository. The shipped deployment is the `Dockerfile` (a plain JVM container).


This document specifies the bare-metal hardware configuration, Linux kernel parameters, low-latency JVM tuning, containerization, and monitoring stack required for ultra-low latency execution.

---

## 1. Low-Latency Systems Architecture

```
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│                        Co-located Bare-Metal Host (Equinix FR2 / LD4)                     │
│  Dual AMD EPYC 9654 (192 Cores) | 512GB DDR5 ECC RAM | Solarflare X2522 Dual-Port 25G NIC  │
└─────────────────────────────────────────────┬─────────────────────────────────────────────┘
                                              │
                                              ▼
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│                           Isolated CPU Cores (isolcpus=2-31,34-63)                        │
│             Core 2-8: Market Data Ingestion  |  Core 9-16: Vol Surface & Pricing            │
│             Core 17-24: Execution Gateway    |  Core 25-32: Real-Time Risk Monitor        │
└─────────────────────────────────────────────┬─────────────────────────────────────────────┘
                                              │
                                              ▼
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│                         Solarflare OpenOnload / EF_VI Network Stack                       │
│                   (User-space network stack bypassing Linux kernel TCP/IP)                │
└─────────────────────────────────────────────┬─────────────────────────────────────────────┘
                                              │
                                              ▼
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│                   Java 21 LTS JVM (Off-Heap Foreign Memory Segment API)                   │
│         - Generational ZGC (-XX:+UseZGC -XX:+ZGenerational)  |  - No GC Pauses (> 1 ms)   │
└───────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Linux Kernel Optimization & Tuning

Production execution nodes require specific Linux boot configuration:

```bash
# /etc/default/grub command line flags
GRUB_CMDLINE_LINUX_DEFAULT="quiet splash isolcpus=2-31,34-63 nohz_full=2-31,34-63 rcu_nocbs=2-31,34-63 processor.max_cstate=0 intel_idle.max_cstate=0 mce=off amd_iommu=on iommu=pt transparent_hugepage=never"
```

### Key Operating System Tuning Flags:
1. **CPU Frequency Scaling**: Locked to maximum performance mode (`cpupower frequency-set -g performance`).
2. **Network Buffer Tuning**:
   ```bash
   sysctl -w net.core.rmem_max=134217728
   sysctl -w net.core.wmem_max=134217728
   sysctl -w net.ipv4.tcp_rmem="4096 87380 67108864"
   sysctl -w net.ipv4.tcp_wmem="4096 65536 67108864"
   sysctl -w net.core.netdev_max_backlog=250000
   ```
3. **Paging & Memory Management**: Disabled swap completely (`swapoff -a`) and enabled 1GB HugePages (`hugepagesz=1G hugepages=64`).

---

## 3. JVM Flags & Off-Heap Memory Engineering

To eliminate Garbage Collection pauses on critical path execution:

### Production JVM Command Line Parameters:
```bash
java \
  -XX:+UseZGC \
  -XX:+ZGenerational \
  -Xms64g -Xmx64g \
  -XX:MetaspaceSize=512m \
  -XX:+UnlockDiagnosticVMOptions \
  -XX:GuaranteedSafepointInterval=0 \
  -XX:+UseLargePages \
  -XX:+AlwaysPreTouch \
  -XX:+UseVectorApi \
  --add-modules jdk.incubator.vector \
  --add-opens java.base/java.nio=ALL-UNNAMED \
  -cp target/classes com.sbk.optionspricer.Main
```

### Zero-Allocation Off-Heap Memory Architecture:
- All market data records, order books, and volatility matrices are stored in off-heap native memory using Java 21 `java.lang.foreign.MemorySegment`.
- Eliminates object allocation on the Java heap during active trading loops, keeping GC pauses at strictly zero during market hours.

---

## 4. Kubernetes & Container Infrastructure

While core execution runs on bare-metal pinned CPUs, supporting services run on Kubernetes:

- **Deployment Services**: Backtesting cluster, historical data ingestion, web analytics UI, risk reports.
- **Monitoring & Metrics**: Prometheus scraping system metrics via JMX Exporter and displaying onGrafana dashboards.
- **Alerting**: PagerDuty integration for hardware failures, networking packet drops, or exchange disconnects.
