# UI/UX Design Language Specification: "Institutional Elegance"

## 1. Core Philosophy
The interface must exude **understated power** and **bravado**. It should feel like a multi-million dollar institutional trading terminal (e.g., Bloomberg, Eikon, or proprietary HFT desks). 
* **Data Density over Decoration:** Every pixel must serve a purpose. Remove all "gamer" aesthetics, glowing neon, and glassmorphism.
* **Understated Elegance:** Let the data and the math speak for themselves. Clean lines, razor-thin borders, and strict semantic coloring.
* **Fluidity:** Interactions must feel frictionless. Transitions should be instantaneous, and massive datasets must render at 60 FPS without dropping frames.

## 2. Color Palette
The color system relies on high-contrast against an ultra-dark, matte background.

| Token | Hex Value | Usage |
| :--- | :--- | :--- |
| `Bg-Primary` | `#0D1117` | Matte deep charcoal/midnight for the primary background. |
| `Bg-Panel` | `#161B22` | Slightly lighter panels to create depth without borders. |
| `Border-Subtle`| `#30363D` | Razor-thin, subtle structural dividers. |
| `Text-Primary` | `#C9D1D9` | High-legibility off-white for main data. |
| `Text-Muted` | `#8B949E` | Secondary labels, table headers, timestamps. |
| `Semantic-Up` | `#238636` | Strict, professional institutional green (Buy/Profit). |
| `Semantic-Down`| `#DA3633` | Strict, professional institutional red (Sell/Loss/Risk). |
| `Accent-Brand` | `#58A6FF` | Minimalist electric blue for primary focus states and active tabs. |

## 3. Typography
Typography must be impeccably crisp, highly legible, and optimized for skimming massive matrices of numbers.

* **UI & Labels:** `Inter` or `Helvetica Neue`. Weights: 400 (Regular), 600 (Semibold). Used for panel headers, navigation, and static text.
* **Data & Metrics:** `Roboto Mono` or `Fira Code`. Weights: 500 (Medium). Essential for tabular alignment (decimal points must align perfectly).

## 4. Layout Architecture (Fluid Grid)
* **Modular Paneling:** The interface uses a fluid CSS Grid. Panels are strictly rectangular with rigid 2px border-radii (almost sharp).
* **Zero Padding Waste:** Internal padding within panels should be extremely tight (`8px` to `12px`) to maximize data density on the screen.
* **Header:** A highly compact top bar containing system status, ping/latency, and firm logo. No wasted vertical space.

## 5. Component Styling
* **Shadows:** Absolutely no drop-shadows or glows. Depth is created strictly through `Bg-Primary` vs `Bg-Panel` contrast.
* **Borders:** Razor-thin (`1px solid var(--border-subtle)`).
* **Buttons:** Flat, brutalist design. Background changes on hover, no scaling animations.

## 6. Animation & Fluidity
* **Micro-interactions:** `100ms ease-out` on hover states (buttons, table rows) to make the UI feel hyper-responsive.
* **Data Streaming:** Ticking data (like the live tape or risk metrics) should flash background color instantly (`Semantic-Up` or `Semantic-Down`) and fade out over `300ms`.
* **Chart Rendering:** All 3D and 2D charts must utilize WebGL (via Plotly) to guarantee 60 FPS rendering even when rotating a 100x100 volatility surface matrix.
