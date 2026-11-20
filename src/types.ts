export type NetworkMode = 'WIFI_ONLY' | 'SMART_AUTO_FAILOVER';
export type AlertChannel = 'TELEGRAM' | 'GMAIL' | 'BOTH';
export type DeliveryTier = 'WIFI' | 'TELCO_DATA' | 'TELCO_SMS' | 'LOCAL_ONLY';
export type MonitoringState = 'IDLE' | 'EXIT_DELAY' | 'ARMED';
export type PhoneTab = 'MONITOR' | 'GALLERY' | 'SETTINGS';
export type StudioView = 'SIMULATOR' | 'SOURCE' | 'ARCHITECTURE' | 'BUILD';

export interface SimNetworkEnvironment {
  wifiConnected: boolean;
  wifiValidated: boolean;
  cellularAvailable: boolean;
  simName: string;
}

export type ZoneLayout = 'FOUR_ZONES' | 'SIX_ZONES';

export interface EmulatorSettings {
  cameraLabel: string;
  sensitivityPercent: number;
  cooldownSeconds: number;
  exitDelayEnabled: boolean;
  exitDelaySeconds: number;
  stealthAutoActivate: boolean;
  networkMode: NetworkMode;
  alertChannel: AlertChannel;
  telegramBotToken: string;
  telegramChatId: string;
  gmailSender: string;
  gmailAppPassword: string;
  gmailRecipients: string;
  smsFallbackNumber: string;
  selectedSimSlot: number;
  deterrentFlashEnabled: boolean;
  deterrentShutterSoundEnabled: boolean;
  requireRegionCrossing: boolean;
  zoneLayout: ZoneLayout;
}

export interface MotionBoundingBox {
  left: number;
  top: number;
  right: number;
  bottom: number;
  centroidX: number;
  centroidY: number;
  zoneLabel: string;
  crossedFromLabel: string | null;
  inGuardband: boolean;
}

export interface SnapshotItem {
  id: string;
  filename: string;
  timestampMs: number;
  cameraLabel: string;
  motionPercent: number;
  deliveryTier: DeliveryTier;
  deliveryStatus: string;
  dataUrl: string;
  compressedKb: number;
}

export interface MotionTelemetryState {
  changedAreaPercent: number;
  meanLumaShift: number;
  globalExposureSuppressed: boolean;
  consecutiveMotionFrames: number;
  cooldownRemainingSeconds: number;
  fps: number;
  boundingBox: MotionBoundingBox | null;
}

export interface FailoverTraceEntry {
  id: string;
  timestampMs: number;
  event: string;
  tier: DeliveryTier;
  detail: string;
}

export interface AndroidFileEntry {
  path: string;
  content: string;
  size: number;
  language: string;
}
