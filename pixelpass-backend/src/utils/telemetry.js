// Behavioural telemetry helpers shared by the scan gateway and the admin API.
//
// The Isolation Forest model only returns a flag and a score, so the gateway has to
// explain each flagged scan in the operator's language: how far apart the two terminals
// are, how fast the pass would have had to travel, how often the account scans, and at
// what hour.

const ACCESS_DECISIONS = ['GRANTED', 'DENIED', 'PROVISIONAL'];

// Two scans inside this radius are treated as the same physical terminal.
const SAME_TERMINAL_METERS = 75;
// Below this distance a jump cannot be an impossible-travel event, only a shared gate.
const IMPOSSIBLE_TRAVEL_METERS = 250;
// Walking pace is ~1.5 m/s; 40 m/s (~144 km/h) is far beyond any legitimate gate-to-gate move.
const IMPOSSIBLE_TRAVEL_METERS_PER_SECOND = 40;
// Scans per rolling hour that stop looking like normal traffic and start looking like a replay.
const SCAN_BURST_SCANS_PER_HOUR = 8;
// A second scan at the same terminal inside this window is a double-scan or a replay.
const RAPID_RESCAN_SECONDS = 60;
// Anything scanned outside campus hours is worth explaining even when the model is right.
const OFF_HOURS_START = 23;
const OFF_HOURS_END = 6;

const toRadians = (degrees) => (degrees * Math.PI) / 180;

// Great-circle distance in metres between two terminal coordinates.
const haversineMeters = (from, to) => {
    const earthRadiusMeters = 6371000;
    const deltaLatitude = toRadians(to.latitude - from.latitude);
    const deltaLongitude = toRadians(to.longitude - from.longitude);
    const a =
        Math.sin(deltaLatitude / 2) ** 2 +
        Math.cos(toRadians(from.latitude)) *
            Math.cos(toRadians(to.latitude)) *
            Math.sin(deltaLongitude / 2) ** 2;

    return 2 * earthRadiusMeters * Math.asin(Math.min(1, Math.sqrt(a)));
};

const formatDuration = (seconds) => {
    if (seconds < 60) return `${Math.round(seconds)}s`;
    if (seconds < 3600) return `${Math.round(seconds / 60)}min`;
    return `${(seconds / 3600).toFixed(1)}h`;
};

const describeAnomaly = ({
    isAnomaly,
    previousLog,
    terminalCoordinate,
    scanIntervalSeconds,
    scanFrequencyOneHour,
    hourOfDay
}) => {
    if (!isAnomaly) return null;

    const distance =
        previousLog?.terminalCoordinate && terminalCoordinate
            ? haversineMeters(previousLog.terminalCoordinate, terminalCoordinate)
            : null;
    const interval = Number.isFinite(scanIntervalSeconds) ? scanIntervalSeconds : null;

    if (distance !== null && interval !== null && interval > 0) {
        const metersPerSecond = distance / interval;
        if (distance >= IMPOSSIBLE_TRAVEL_METERS && metersPerSecond >= IMPOSSIBLE_TRAVEL_METERS_PER_SECOND) {
            return `Impossible travel: ${Math.round(distance)} m in ${formatDuration(interval)}`;
        }
    }

    if (Number.isFinite(scanFrequencyOneHour) && scanFrequencyOneHour >= SCAN_BURST_SCANS_PER_HOUR) {
        return `Scan burst: ${scanFrequencyOneHour} scans in the last hour`;
    }

    if (
        distance !== null &&
        interval !== null &&
        distance <= SAME_TERMINAL_METERS &&
        interval < RAPID_RESCAN_SECONDS
    ) {
        return `Rapid re-scan: ${formatDuration(interval)} after the previous scan at the same terminal`;
    }

    if (Number.isFinite(hourOfDay) && (hourOfDay < OFF_HOURS_END || hourOfDay >= OFF_HOURS_START)) {
        return `Off-hours scan at ${String(hourOfDay).padStart(2, '0')}:00`;
    }

    return 'Behavioural anomaly flagged by the Isolation Forest model';
};

// A matching face is granted unless the behaviour is flagged, in which case the guard is
// told the pass is provisional and the console keeps the scan for review.
const decideAccess = ({ biometricMatch, isAnomaly }) => {
    if (!biometricMatch) return 'DENIED';
    return isAnomaly ? 'PROVISIONAL' : 'GRANTED';
};

module.exports = { ACCESS_DECISIONS, describeAnomaly, decideAccess, haversineMeters };
