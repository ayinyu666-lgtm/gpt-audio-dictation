#!/usr/bin/env bash
# Host-only JVM tests for the pure-Java part of the assistant.
# These classes deliberately avoid Android APIs so they can run without a device.
set -euo pipefail

cd "$(dirname "$0")"
FIXTURE_ROOT="${GAD_MOBILE_FIXTURES_ROOT:-}"
SRC=app/src/main/java
TEST=app/src/hostTest/java
OUT=build/host-test-classes

rm -rf "$OUT"
mkdir -p "$OUT"

PURE=(
  ActivityMarginChange AdaptivePausePolicy AwayPolicy CaptureLease DiagnosticHasher FreezeDetector HeaderContext
  ManualTouchPolicy NoiseAdaptation NoiseIgnorePolicy OverlayStatus RecordingFlowPolicy RecordingGuard RecordingSettings
  ResumePolicy SendButtonGeometry SessionNaming SessionNamingPolicy WaveformDetector WaveformSamplingPolicy VisualCropTracker
  RecordingRound SegmentSubmitDecision EnvProfile EnvLearnSession AdaptiveSamplingPolicy ScanLabels TickWatchdog ProjectionGeometry
  DeadlinePressurePolicy
)

FILES=()
for name in "${PURE[@]}"; do
  FILES+=("$SRC/org/gptdictation/assistant/$name.java")
done
FILES+=("$TEST"/org/gptdictation/assistant/*.java)

javac -encoding UTF-8 -d "$OUT" "${FILES[@]}"

CLASSES=(
  ActivityMarginChangeTest AdaptivePausePolicyTest AwayAndManualTest CaptureLeaseTest DetectorSegmentReuseTest
  DiagnosticHasherTest FlatQuietTest FreezeDetectorTest HeaderContextTest NoiseAdaptationTest NoiseIgnorePolicyTest RebaselineSafetyTest
  RecordingFlowPolicyTest
  RecordingGuardTest ResumePolicyTest SendButtonGeometryTest SessionNamingPolicyTest SessionNamingScenarioTest SessionNamingTest
  WaveformDetectorTest WaveformSamplingPolicyTest VisualCropTrackerTest
  RecordingRoundTest SegmentSubmitDecisionTest IdleCalibrationTest EnvLearningTest HysteresisTest RealWaveformTest ShareBarLearningTest AdaptiveSamplingTest
  ScanLabelsTest DegenerateSegmentTest TickLoopHardeningTest ProjectionGeometryTest DeadlinePressurePolicyTest
)

WAVE_DIR="$PWD/app/src/hostTest/resources/waveform"

for c in "${CLASSES[@]}"; do
  case "$c" in
    WaveformDetectorTest|RecordingRoundTest)
      java -Dfile.encoding=UTF-8 -cp "$OUT" org.gptdictation.assistant."$c" "$FIXTURE_ROOT" ;;
    RealWaveformTest)
      java -Dfile.encoding=UTF-8 -Dwaveform.dir="$WAVE_DIR" -cp "$OUT" org.gptdictation.assistant."$c" ;;
    *)
      java -Dfile.encoding=UTF-8 -cp "$OUT" org.gptdictation.assistant."$c" ;;
  esac
done

echo "All ${#CLASSES[@]} host JVM test classes passed"
