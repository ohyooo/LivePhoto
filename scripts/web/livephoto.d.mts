export interface WebResult {
  readonly success: boolean;
  readonly code: string;
  readonly stage: string;
  readonly message: string;
  readonly protocol: string;
  readonly verdict: string;
  readonly coverage: string;
  readonly mime: string;
  readonly issues: readonly string[];
  readonly blob: Blob | null;
  readonly assets: readonly WebAsset[];
  readonly report: WebReport | null;
}

/** Int64/UInt64 and exact decimal metadata are strings, never rounded JS numbers. */
export type JsonValue = null | boolean | number | string | readonly JsonValue[] | { readonly [key: string]: JsonValue };
export interface WebReport {
  readonly kind: 'Detection' | 'Validation' | 'Inspection' | 'Analysis' | 'KeyPhoto' | 'Operation' | 'Repair' | 'Error' | 'MediaProbe';
  readonly [key: string]: JsonValue;
}
export interface WebAsset {
  readonly id: string;
  readonly role: string;
  readonly mime: string;
  readonly byteLength: string;
  readonly digest: string;
  readonly recommendedName: string | null;
  readonly blob: Blob;
}

export interface CreateOptions {
  target?: 'oplus.olive' | 'google.motionphoto.v2' | 'google.microvideo.v1';
  replaceCameraComment?: boolean;
}

export function createLivePhoto(image: Blob, video: Blob, options?: CreateOptions): Promise<WebResult>;
export function detectLivePhoto(input: Blob): Promise<WebResult>;
export function validateLivePhoto(input: Blob): Promise<WebResult>;
export function extractVideo(input: Blob): Promise<WebResult>;
export function inspectLivePhoto(input: Blob): Promise<WebResult>;
export function analyzeLivePhoto(input: Blob): Promise<WebResult>;
export function getKeyPhotoPosition(input: Blob): Promise<WebResult>;
export function splitLivePhoto(input: Blob): Promise<WebResult>;
export function convertLivePhoto(input: Blob, options: { target: NonNullable<CreateOptions['target']> }): Promise<WebResult>;

export interface RepairOptions {
  /** Defaults to true. False explicitly applies only proven-safe metadata changes. */
  dryRun?: boolean;
  /** Empty permits all proven-safe changes. Does not authorize remux or metadata loss. */
  allowedIssueCodes?: readonly string[];
}
/** Inspect report.blocked/changesApplied/operation; success alone does not mean repaired. */
export function repairLivePhoto(input: Blob, options?: RepairOptions): Promise<WebResult>;

export interface BrowserMediaBackend {
  probe(input: Blob): Promise<WebResult>;
  remux(input: Blob): Promise<WebResult>;
  trim(input: Blob, options: { start: number; end: number }): Promise<WebResult>;
  extractFrame(input: Blob, options?: { start?: number }): Promise<WebResult>;
  /** Only explicit authorizeTranscode:true permits video encoding. Audio is copy only. */
  transcode(input: Blob, options?: { authorizeTranscode?: boolean }): Promise<WebResult>;
}
/** Optional same-origin runtime + vendor assets must be hosted separately. Finite Experimental profiles. */
export function createMediaBackend(options?: { runtimeUrl?: string | URL; signal?: AbortSignal;
  /** Opt-in bounded diagnostics may contain private metadata; do not upload automatically. */
  onDiagnostic?: (value: {code: number | null; output: string; cancelled: boolean; timedOut: boolean; ioFailed: boolean}) => void;
}): BrowserMediaBackend;
