/** Scoped styles for the browser's independent, non-overlay video player. */
export const playerStyle = `
.lp-player{width:100%;height:100%;display:flex;justify-content:center;align-items:flex-start;color:#0f172a;font:13px system-ui,sans-serif;box-sizing:border-box}
.lp-player *{box-sizing:border-box}
.lp-player .lp-card{background:#fff;padding:10px;border:1px solid #0f172a14;border-radius:14px;box-shadow:0 4px 20px #0f172a0f;max-width:100%;min-width:0}
.lp-player .lp-stage{display:flex;gap:10px;align-items:stretch;max-width:100%}
.lp-player video{display:block;object-fit:contain;border-radius:11px;background:#f5f7fb;min-width:0;cursor:pointer}
.lp-player .lp-controls{display:grid;align-items:center;gap:4px;min-height:52px;margin-top:8px;border-radius:10px;background:#fff;grid-template-columns:32px max-content minmax(24px,1fr) 32px 52px 44px 32px}
.lp-player button{height:32px;min-width:0;padding:5px;border:0;border-radius:7px;background:transparent;color:#0f172a;cursor:pointer;display:inline-flex;align-items:center;justify-content:center;transition:background 150ms,color 150ms;font:12px system-ui}
.lp-player button:hover{background:#f1f5f9}.lp-player button:active{background:#e2e8f0}.lp-player button:disabled{opacity:.4;cursor:default}
.lp-player button:focus-visible,.lp-player [role=slider]:focus-visible,.lp-player input:focus-visible{outline:2px solid #3b82f6;outline-offset:2px}
.lp-player svg{width:20px;height:20px;fill:none;stroke:currentColor;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}
.lp-player .lp-time{font-size:12px;font-variant-numeric:tabular-nums;white-space:nowrap;color:#64748b}
.lp-player .lp-progress{position:relative;min-width:24px;height:28px;cursor:pointer;touch-action:none;--p:0%}
.lp-player .lp-track{position:absolute;left:7px;right:7px;top:12px;height:4px;border-radius:3px;background:#cbd5e1}
.lp-player .lp-fill{position:absolute;inset:0 auto 0 0;width:var(--p);border-radius:inherit;background:#3b82f6}
.lp-player .lp-thumb{position:absolute;width:14px;height:14px;top:50%;left:var(--p);transform:translate(-50%,-50%);background:white;border:1px solid #cbd5e1;border-radius:50%;box-shadow:0 1px 3px #0f172a25}
.lp-player .lp-volume{accent-color:#3b82f6;width:52px;min-width:0;height:28px;margin:0}
.lp-player .lp-speed{border:1px solid #0f172a14}
.lp-player .lp-rates{display:flex;flex-wrap:wrap;gap:4px;justify-content:center;padding-top:4px;border-top:1px solid #0f172a0c}
.lp-player .lp-rates[hidden]{display:none}.lp-player .lp-rates button[aria-pressed=true]{color:#3b82f6;background:#eff6ff}
.lp-player.lp-compact .lp-volume{display:none}.lp-player.lp-compact .lp-controls{grid-template-columns:30px max-content minmax(24px,1fr) 30px 42px 30px;gap:2px}
.lp-player.lp-portrait .lp-controls{grid-template-columns:32px 32px minmax(24px,52px) 44px 32px;justify-content:space-between}
.lp-player.lp-portrait.lp-compact .lp-controls{grid-template-columns:32px 32px 44px 32px;gap:1px}
.lp-player.lp-portrait .lp-time{display:none}
.lp-player.lp-portrait .lp-progress{height:auto;min-width:24px;width:24px;flex:none}
.lp-player.lp-portrait .lp-track{top:7px;bottom:7px;left:10px;right:auto;width:4px;height:auto}
.lp-player.lp-portrait .lp-fill{inset:0 0 auto 0;width:auto;height:var(--p)}
.lp-player.lp-portrait .lp-thumb{left:50%;top:var(--p)}
.lp-player:fullscreen{background:#f5f7fb;width:100vw;height:100vh;align-items:center;padding:12px;overflow:auto}
.lp-player [aria-disabled=true]{opacity:.4;cursor:default}
@media(prefers-reduced-motion:reduce){.lp-player button{transition:none}}
`;
