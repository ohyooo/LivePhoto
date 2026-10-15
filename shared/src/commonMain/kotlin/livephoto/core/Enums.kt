package livephoto.core

public enum class Stage { Read, Parse, Detect, Inspect, Validate, Plan, Extract, Clean, Trim, Remux, Transcode, DecodeFrame, EncodeImage, WriteProtocol, Verify, Publish }
public enum class Operation { Detect, Analyze, Inspect, Validate, Create, ConvertFrom, ConvertTo, ExtractRaw, SplitClean, Repair, GetKey, SetKey, ReplaceCover, Probe, Trim, Remux, Transcode, ExtractFrame }
public enum class Severity { Info, Warning, Error }
public enum class Layer { Structure, Protocol, Media, Preservation, Compatibility }
public enum class Verdict { Valid, Warning, Invalid }
public enum class Coverage { Complete, Partial, NotRun }
public enum class Recoverability { Never, AfterRepair, WithDifferentPolicy, WithBackend, WithMatchingAsset, AfterRetry, AfterImplementation }
public enum class Repairability { Safe, Conditional, NotRepairable, Unknown }
public enum class Disposition { Live, NonLive, Candidate, Ambiguous, Unknown }
public enum class MatchStrength { Strong, CompatibleBase, Weak, Legacy }
public enum class Implementation { Supported, Experimental, Planned, Unsupported }
public enum class Lifecycle { Active, Legacy }
public enum class Exposure { Public, Internal, Hidden }
public enum class Verification { SourceReviewed, SyntheticTested, DeviceTested }
public enum class Availability { Supported, Conditional, Unsupported }
public enum class FactOrigin { Parsed, Backend, Estimated, Unknown }
public enum class ImageFormat { Jpeg, Heic, HeifOther, Avif, Png, Unknown }
public enum class VideoContainer { Mp4, Mov, Unknown }
public enum class VideoCodec { Avc, Hevc, Av1, Other, Unknown }
public enum class AudioCodec { Aac, Pcm, Other, Unknown }
public enum class AssetRole { PrimaryImage, MotionVideo, SidecarMetadata, AuxiliaryImage, VendorTrailer, Composite }
public enum class ResourceKind { PrimaryImage, Video, GainMap, Depth, Thumbnail, Exif, Xmp, Icc, VendorMetadata, Trailer, Padding, Unknown }
public enum class RelationshipKind { AuxiliaryOf, Describes, PairedWith, SharedData }
public enum class Ownership { SourceProtocol, TargetProtocol, Ordinary, StandardImage, Unknown }
public enum class Selection { AtOrBefore, Nearest, Exact }
public enum class KeySource { ProtocolField, TimedMetadataSample, DerivedDefault, Unknown }
public enum class KeyOutsidePolicy { Reject, ClampExplicitly, ClearIfSupported }
public enum class SplitMode { Clean, Raw }
public enum class TrimMode { LosslessOnly, LosslessPreferred, Exact }
public enum class BoundaryPolicy { CoverRequestedRange, StayWithinRequestedRange, NearestSafe }
public enum class PrerollPolicy { RejectHiddenRetainedContent, AllowWithDisclosure }
public enum class TranscodePolicy { Forbid, WhenRequired, Explicit }
public enum class PreservationPolicy { Strict, BestEffortWithReport }
public enum class LossPolicy { RejectUnrequested, AllowListed }
public enum class Guarantee { ExactExtraction, BitstreamPreserving, ImageDataPreserving, MetadataPreserving }
public enum class GuaranteeOutcome { Verified, Changed, Unknown, NotApplicable }
public enum class ConflictPolicy { Reject, ExplicitAuthority }
public enum class ExistingOutput { Fail, Replace }
public enum class Atomicity { AssetSetRequired, PerAssetExplicitlyAccepted }
public enum class TransactionState { Open, Prepared, Verified, Committed, Aborted, Indeterminate }
public enum class RepairMode { SafeMetadataOnly, ExplicitRemux, ExplicitRePair }
public enum class SameTargetPolicy { PreserveAsIs, Normalize }
public enum class SourceBindingPolicy { RejectAlreadyLive, StripSourceBindings }
public enum class DynamicRangePolicy { Preserve, ExplicitToneMapToSdr }
public enum class OrientationPolicy { PreserveTransform, BakeIntoPixels }
public enum class FrameRatePolicy { Preserve, ExplicitConstantRate }
public enum class EvidenceKind { Official, SourceCode, AuthorObservation, Inspection, DeviceTest, Inference }
public enum class ConditionOperator { All, Any, Not, Equals, Contains, AtLeast, Present }
