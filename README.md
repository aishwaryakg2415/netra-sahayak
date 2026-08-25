# netra-sahayak
Netra Sahayak is a lightweight, mobile-first UI mockup for an offline-first eye screening app. This repository currently contains a static HTML prototype (netra-app-ui-mockup.html) that demonstrates the three-step screening flow: Capture → Analyze (on-device) → Result (clear, actionable guidance).

Live demo (file in this repo): https://github.com/aishwaryakg2415/netra-sahayak/blob/main/netra-app-ui-mockup.html

Goal
Make screening quick and unambiguous for health workers with minimal training. The UI focuses on one action per screen, clear visual explanation of flagged regions, and an always-visible offline state.

Key design principles
One action per screen: capture, wait, then read.
Highlight what the model flagged instead of relying only on confidence numbers.
Offline-first: analysis is shown as running on-device; no mandatory network required.
Plain language outcomes: e.g., "Refer to a doctor" or "Looks healthy".
What’s in this repo
netra-app-ui-mockup.html — static HTML/CSS/JS prototype of the three-screen flow and interactions.
How to preview
You can open the HTML file directly in a browser, or serve it locally:

Open directly:

Double-click netra-app-ui-mockup.html or open it in your browser.
Serve via a simple HTTP server (recommended for correct asset loading):

Python 3:
python3 -m http.server 8000
then open http://localhost:8000/netra-app-ui-mockup.html
Notes for developers
The prototype is purely client-side — no machine learning or backend is included yet.
The UI includes a mock "glow" that shows the region the model would flag; replace this with actual model output when integrating.
Accessibility:
Buttons support focus styles; consider adding ARIA labels and screen reader text for production.
Consider color-contrast and color-blind friendly palettes before production release.
Roadmap / TODOs
Integrate on-device model inference or a secure local inference pipeline.
Add image validation & capture feedback for low-quality images.
Implement persistent local storage for patient metadata and referral slips.
Add tests and automated visual regression checks.
Add license and contribution guidelines.
Contributing
Contributions are welcome. If you'd like to add features or improve the prototype:

Fork the repo, make changes, and open a pull request.
Describe proposed changes and include screenshots or a short demo.
