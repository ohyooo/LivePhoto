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
}

export interface CreateOptions {
  target?: 'oplus.olive' | 'google.motionphoto.v2' | 'google.microvideo.v1';
  replaceCameraComment?: boolean;
}

export function createLivePhoto(image: Blob, video: Blob, options?: CreateOptions): Promise<WebResult>;
export function detectLivePhoto(input: Blob): Promise<WebResult>;
export function validateLivePhoto(input: Blob): Promise<WebResult>;
export function extractVideo(input: Blob): Promise<WebResult>;
