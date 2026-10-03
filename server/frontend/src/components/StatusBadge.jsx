/**
 * StatusBadge – hiển thị badge trạng thái (online/offline, PENDING/DONE/ERROR/TIMEOUT, sensor/actuator).
 */
export function OnlineBadge({ online }) {
  return (
    <span className={`badge ${online ? 'badge-online' : 'badge-offline'}`}>
      {online ? 'Online' : 'Offline'}
    </span>
  );
}

export function CommandStatusBadge({ status }) {
  const cls = {
    PENDING: 'badge-pending',
    DONE: 'badge-done',
    ERROR: 'badge-error',
    TIMEOUT: 'badge-timeout',
  }[status] || 'badge-pending';

  return <span className={`badge ${cls}`}>{status}</span>;
}

export function DeviceTypeBadge({ type }) {
  const cls = type === 'sensor' ? 'badge-sensor' : 'badge-actuator';
  return <span className={`badge ${cls}`}>{type}</span>;
}
