# Bloomberg Terminal & High-Frequency Trading Interface Specification (DESIGN.md)

## 1. Core Philosophy & Terminal Aesthetics
The interface is engineered to emulate the iconic **Bloomberg Terminal (BBG)** and modern HFT execution cockpits. It prioritizes ultra-high data density, instant visual recognition, zero-friction navigation, and mathematical authority.

* **Monochrome Pitch-Black Background**: High-contrast matte black (`#05070A` / `#000000`) canvas designed to minimize eye fatigue during extended trading sessions.
* **Iconic Bloomberg Amber & Cyan Accent System**: High-visibility Bloomberg Safety Gold/Amber (`#FF9900`) for active command prompts and core numerical metrics, complemented by Neon Terminal Cyan (`#00E5FF`) for headers, tickers, and navigation keys.
* **Brutalist Zero-Padding Density**: Maximum data per square inch. No drop shadows, floating cards, or wasteful rounded borders—only razor-thin 1px structural dividers.
* **Monospaced Data Precision**: Monospaced tabular alignment ensuring all monetary values, basis points, and option Greeks align vertically.

---

## 2. Color Palette Matrix

| Token | Hex Value | Usage / Role |
| :--- | :--- | :--- |
| `Bg-Terminal` | `#05070A` | Pitch-black main canvas background. |
| `Bg-Panel` | `#0C0F14` | High-density panel background. |
| `Border-Terminal`| `#1C232D` | 1px razor-thin panel outline. |
| `Border-Amber` | `#FF9900` | Active window focus highlight & prompt border. |
| `Text-Amber` | `#FF9900` | Primary Bloomberg accent, hotkey buttons, core metrics. |
| `Text-Cyan` | `#00E5FF` | Ticker symbols, panel header labels, command tokens. |
| `Text-Primary` | `#E0E6ED` | Off-white tabular metrics and high-legibility text. |
| `Text-Muted` | `#5C6B73` | Table column headers, historical timestamps. |
| `Semantic-Up` | `#00E676` | Institutional trade buy tick / profit green. |
| `Semantic-Down`| `#FF3D00` | Institutional trade sell tick / risk red / SPAN margin alert. |

---

## 3. Terminal Navigation & Command Prompt System

The UI features an interactive **Bloomberg Command Header**:
- **Prompt Format**: `AURA-OPT > [COMMAND] <GO>`
- **Supported Command Sequences**:
  - `VOLS <GO>` / `F3`: Focuses on the 3D SABR Volatility Surface & Smile curves.
  - `RISK <GO>` / `F4`: Inspects live Net Delta, Net Gamma, Net Vega & SPAN Margin requirements.
  - `TICK <GO>` / `F2`: Expands high-frequency live market tick tape.
  - `HELP <GO>` / `F1`: Displays terminal operating manual & quantitative shortcuts.

---

## 4. Typography & Grid Layout Architecture

* **Font Stack**: Primary Monospace (`'JetBrains Mono'`, `'Roboto Mono'`, `'Courier New'`, monospace) for tabular grid and numerical outputs; Terminal UI Sans (`'Inter'`, sans-serif) for system headers.
* **4-Quadrant Grid Architecture**:
  - **Top Bar**: Command Line Input Box, Latency Ping Counter, Exchange Connectivity Status (`EUREX`, `EURONEXT`, `LSEG`, `LMAX IPC`).
  - **Left Quadrant**: Portfolio Risk Matrix & Real-time System Event Log.
  - **Center Quadrant**: WebGL-powered 3D SABR Volatility Surface with 2D Smile and Term Structure charts (Amber/Cyan colorscale).
  - **Right Quadrant**: High-frequency Order Book & Live Execution Tape.
  - **Footer Ticker**: Real-time European market index ticker ribbon (FDAX, FSTX50, CAC40, FTSE100).

---

## 5. Micro-Animations & Data Streaming

* **Tick Flash Updates**: Executed buy/sell ticks instantly flash `Semantic-Up` (`#00E676`) or `Semantic-Down` (`#FF3D00`) background cells with a `300ms` decay curve.
* **Real-Time WebGL Rendering**: 3D surface plot renders at 60 FPS using Plotly WebGL integration with Bloomberg Amber-to-Cyan color gradients.

