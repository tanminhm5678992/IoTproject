/**
 * DeviceCard – Thẻ thiết bị phong cách Home Assistant Tile Card.
 * Tích hợp icon thực thể, chỉ số cảm biến nổi bật, trạng thái trực quan và Lovelace Quick Action Features.
 */
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { OnlineBadge, DeviceTypeBadge } from './StatusBadge';
import { sendCommand } from '../api/client';
import toast from 'react-hot-toast';
import {
  Thermometer, Droplets, Cpu, Zap, ChevronRight, Power, Loader2, Sparkles,
} from 'lucide-react';

function formatTime(ts) {
  if (!ts) return 'Chưa có dữ liệu';
  const d = new Date(ts);
  return d.toLocaleString('vi-VN', {
    hour: '2-digit', minute: '2-digit', second: '2-digit',
    day: '2-digit', month: '2-digit',
  });
}

export default function DeviceCard({ device }) {
  const navigate = useNavigate();
  const isOnline = !!device.online;
  const isActuator = device.type === 'actuator';

  // Trạng thái local cho quick action
  const [quickStates, setQuickStates] = useState({
    relay1: false,
    relay2: false,
  });
  const [pendingQuick, setPendingQuick] = useState(null);

  // Xử lý gửi lệnh nhanh từ bên ngoài thẻ (Home Assistant Inline Tile Action)
  const handleQuickToggle = async (e, target) => {
    e.stopPropagation(); // Ngăn kích hoạt navigate vào chi tiết
    if (!isOnline) {
      toast.error('Thiết bị đang ngoại tuyến, không thể gửi lệnh');
      return;
    }

    const nextVal = quickStates[target] ? 'OFF' : 'ON';
    setPendingQuick(target);
    try {
      await sendCommand(device.deviceId, target, nextVal);
      setQuickStates((prev) => ({ ...prev, [target]: nextVal === 'ON' }));
      toast.success(`⚡ [${device.name}] ${target.toUpperCase()} → ${nextVal}`);
    } catch (err) {
      toast.error(err.response?.data?.message || `Lỗi gửi lệnh ${target}`);
    } finally {
      setPendingQuick(null);
    }
  };

  return (
    <div
      className={`ha-tile-card ${isOnline ? 'online' : 'offline'} fade-in`}
      onClick={() => navigate(`/devices/${device.deviceId}`)}
      role="button"
      tabIndex={0}
      id={`device-card-${device.deviceId}`}
    >
      {/* Phần trên: Icon thực thể + Tên + Badge trạng thái */}
      <div className="ha-tile-top">
        <div className="ha-tile-entity">
          <div
            className={`ha-icon-badge ${
              isActuator
                ? quickStates.relay1 || quickStates.relay2
                  ? 'actuator bulb-on'
                  : 'actuator'
                : 'sensor'
            }`}
          >
            {isActuator ? <Zap size={22} /> : <Cpu size={22} />}
          </div>
          <div className="ha-tile-meta">
            <h3>{device.name}</h3>
            <div className="ha-device-id">{device.deviceId}</div>
          </div>
        </div>

        <div className="ha-badges-group">
          <OnlineBadge online={device.online} />
          <DeviceTypeBadge type={device.type} />
        </div>
      </div>

      {/* Chỉ số cảm biến môi trường (Lovelace Metric Boxes) */}
      <div className="ha-metrics-row">
        <div className="ha-metric-box">
          <div className="ha-metric-icon temp">
            <Thermometer size={18} />
          </div>
          <div className="ha-metric-info">
            <span className="ha-metric-label">Nhiệt độ</span>
            <span className="ha-metric-val temp-text">
              {device.latestTelemetry?.temp != null
                ? `${device.latestTelemetry.temp.toFixed(1)}°C`
                : '—'}
            </span>
          </div>
        </div>

        <div className="ha-metric-box">
          <div className="ha-metric-icon hum">
            <Droplets size={18} />
          </div>
          <div className="ha-metric-info">
            <span className="ha-metric-label">Độ ẩm</span>
            <span className="ha-metric-val hum-text">
              {device.latestTelemetry?.hum != null
                ? `${device.latestTelemetry.hum.toFixed(1)}%`
                : '—'}
            </span>
          </div>
        </div>
      </div>

      {/* Home Assistant Tile Features (Quick inline actions cho thiết bị chấp hành) */}
      {isActuator && isOnline && (
        <div className="ha-tile-features" onClick={(e) => e.stopPropagation()}>
          <button
            type="button"
            className={`ha-feature-btn ${quickStates.relay1 ? 'is-active' : ''} ${
              pendingQuick === 'relay1' ? 'is-pending' : ''
            }`}
            onClick={(e) => handleQuickToggle(e, 'relay1')}
            disabled={pendingQuick === 'relay1'}
            title="Bật/Tắt Rơ-le cổng 1 tức thì"
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.4rem' }}>
              {pendingQuick === 'relay1' ? (
                <Loader2 size={13} className="loading-spinner" />
              ) : (
                <Power size={13} />
              )}
              <span>Relay 1: {quickStates.relay1 ? 'BẬT' : 'TẮT'}</span>
            </div>
            <span className="ha-feature-status-dot" />
          </button>

          <button
            type="button"
            className={`ha-feature-btn ${quickStates.relay2 ? 'is-active' : ''} ${
              pendingQuick === 'relay2' ? 'is-pending' : ''
            }`}
            onClick={(e) => handleQuickToggle(e, 'relay2')}
            disabled={pendingQuick === 'relay2'}
            title="Bật/Tắt Rơ-le cổng 2 tức thì"
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.4rem' }}>
              {pendingQuick === 'relay2' ? (
                <Loader2 size={13} className="loading-spinner" />
              ) : (
                <Power size={13} />
              )}
              <span>Relay 2: {quickStates.relay2 ? 'BẬT' : 'TẮT'}</span>
            </div>
            <span className="ha-feature-status-dot" />
          </button>
        </div>
      )}

      {/* Chân thẻ: Trạng thái & Lần cuối cập nhật */}
      <div className="ha-tile-footer">
        <div style={{ display: 'flex', alignItems: 'center' }}>
          <span className={`ha-status-dot ${isOnline ? 'online' : 'offline'}`} />
          <span>{isOnline ? 'Hoạt động tốt' : 'Mất kết nối'}</span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.25rem' }}>
          <span>{formatTime(device.lastSeen)}</span>
          <ChevronRight size={14} />
        </div>
      </div>
    </div>
  );
}
