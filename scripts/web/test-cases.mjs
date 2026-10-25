// Each entry is an independent browser scenario; assertions within it share state.
const protocols=['oplus.olive','google.motionphoto.v2','google.microvideo.v1'];
const invalid=['ordinary','malformed','budget'];
export const uiCases={
  create:['subpath-assets-loaded','initial-create-disabled','appbar-does-not-cover-picker','shared-file-names-and-sizes','cancel-keeps-selection',
    ...protocols.map(p=>p+'-download-valid-exact-video'),'honest-download-status','result-hold-preview','viewport-contained','malformed-input-error-visible',
    'responsive-workspace-retains-video','pending-trim-and-reset-draft','input-removal-and-reset-confirmation'],
  analysis:[...protocols.map(p=>'analysis-live-'+p),'analysis-ordinary-not-live','analysis-unknown-not-nonlive','analysis-malformed-error-not-nonlive',
    'analysis-budget-before-parse','analysis-cancel-keeps-report','analysis-does-not-alter-create-state'],
  extract:['extract-oplus.olive-exact-video',...invalid.map(p=>'extract-'+p+'-no-output'),'extract-clears-stale-report','extract-preserves-analysis-and-create-state'],
  split:['split-oplus.olive-clean-independent-files',...invalid.map(p=>'split-'+p+'-no-output'),'split-clears-stale-preparation','split-preserves-analysis-and-create-state'],
  convert:['convert-oplus.olive-to-google.motionphoto.v2','convert-google.motionphoto.v2-to-oplus.olive','convert-google.microvideo.v1-to-google.microvideo.v1',
    ...invalid.map(p=>'convert-'+p+'-no-output'),'convert-clears-stale-report','convert-preserves-analysis-and-create-state'],
  repair:[...['valid-no-output','preview-no-output','applied-preserving','idempotent-no-output'].map(c=>'repair-google.microvideo.v1-'+c),
    'repair-oplus-zero-padding-preserving','repair-blocked-key-no-output','repair-source-changed-no-output',...invalid.map(p=>'repair-'+p+'-no-output'),
    'repair-selection-state-isolated','repair-preserves-analysis-and-create-state'],
  key:['key-google.microvideo.v1-preserving','key-oplus.olive-refused','key-invalid-value-no-output','key-outside-no-output',
    ...invalid.map(p=>'key-'+p+'-no-output'),'key-selection-state-isolated','key-preserves-analysis-and-create-state'],
};
const playerCases=['tools-collapsed-by-default','aspect-and-viewport','controls-outside-picture','native-overlays-disabled','correct-progress-axis',
  'pointer-seek-paused','keyboard-seek','play-and-pause','picture-click-toggle','mute-and-restore','rate-menu-outside-picture','fullscreen-ratio-controls',
  'ended-progress-complete','current-picture-capture','range-handles-select-inner-segment'];
const themeCases=['default-follows-dark-browser','browser-preference-updates-rendering','toggle-in-top-right','manual-toggle-keeps-selected-media',
  'manual-override-is-respected','reload-follows-browser-again'];
const browserCases=protocols.flatMap(p=>['create','detect','validate','extract','inputs-immutable','embedded-video','inspect-layout','analyze-no-media-backend',
  'key-position','clean-split-assets','same-target-exact'].map(n=>p+':'+n).concat(protocols.filter(q=>q!==p).map(q=>p+':'+(p==='oplus.olive'&&q==='google.motionphoto.v2'?'convert-blocked-exif-':'convert-to-')+q)))
  .concat(['ordinary-jpeg-is-not-live','unknown-target-rejected','swapped-content-rejected','truncated-video-no-output','budget-rejected-before-reading',
    'invalid-js-input-rejected','convert-unknown-target-no-assets','convert-ordinary-is-not-create','inspect-unknown-and-malformed-structured-results','convert-target-required'])
  .concat(protocols.flatMap(p=>['repair-valid-noop','repair-dry-run','repair-filter-blocked','repair-applied-preserving','repair-idempotent-no-output'].map(n=>p+':'+n)))
  .concat(['repair-invalid-options-rejected','repair-budget-before-reading','repair-ordinary-no-output','repair-malformed-no-output','repair-ambiguous-suffix-no-output']);
const sizes=['mobile','wide'];
export const testPlan=[
  ...Object.entries(uiCases).flatMap(([scenario,cases])=>sizes.map(viewport=>({suite:'ui',part:scenario+'-'+viewport,scenario,viewport,checks:cases.map(c=>viewport+':'+c)}))),
  ...sizes.flatMap(size=>['landscape','portrait','square'].map(shape=>({suite:'player',part:size+'-'+shape,
    checks:[...(size==='mobile'&&shape==='square'?['vfr-source-independently-confirmed']:[]),...playerCases.map(c=>size+':'+shape+':'+c)]}))),
  ...sizes.map(size=>({suite:'theme',part:size,checks:themeCases.map(c=>size+':'+c)})),
  {suite:'browser',part:'main',checks:browserCases}, {suite:'browser',part:'worker',checks:['module-worker-round-trip']},
  ...['main','worker',...sizes].map(part=>({suite:'media',part,checks:part==='main'?['invalid-range-rejected','core-probe-decode-partial',
    'core-remux-independent-video-audio','core-trim-actual-timeline','core-transcode-explicit-audio-copy','core-extract-frame-decodes',
    'core-policy-and-malformed-no-output','core-cancel-and-budget-no-output','core-input-bytes-immutable']:[]})),
  ...['mono','surround'].flatMap(channels=>['remux','trim','transcode'].map(action=>({suite:'media',part:`aac-${channels}-${action}`,checks:[`aac-${channels}-${action}-channel-preservation`]}))),
];
export const evidenceName=suite=>suite==='ui'?'ui-browser':suite;
