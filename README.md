# Aegis Debug landing site

Hand-rolled static site. To preview locally:

```bash
python3 -m http.server 8000 --directory site
```

Then open <http://localhost:8000>.

No build step. No dependencies. Deployed via `.github/workflows/site.yml` to GitHub Pages on every push to `main` that touches `site/**`.
