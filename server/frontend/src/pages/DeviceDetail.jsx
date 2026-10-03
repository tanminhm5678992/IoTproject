/**
 * Trang DeviceDetail – Chi tiết thiết bị phong cách Home Assistant Entity View.
 * Bố cục thẻ Lovelace: thông tin chỉ số môi trường, biểu đồ AreaChart,
 * bảng công tắc tương tác và nhật ký hoạt động thời gian thực.
 */
import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { getDevice, deleteDevice } from '../api/client';
import { OnlineBadge, DeviceTypeBadge } from '../components/StatusBadge';
import TelemetryChart from '../components/TelemetryChart';
import CommandPanel from '../components/CommandPanel';
import useStomp from '../hooks/useStomp';
import toast from 'react-hot-toast';
import {
  ArrowLeft, Trash2, Cpu, Zap, Thermometer, Droplets, Clock, Tag,
} from 'lucide-react';

function formatTime(ts) {
  if (!ts) return '—';
  return new Date(ts).toLocaleString('vi-VN');
}

export default function DeviceDetail() {
  const { deviceId } = useParams();
  const navigate = useNavigate();
  const [device, setDevice] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [realtimeTelemetry, setRealtimeTelemetry] = useState(null);
  const [realtimeCmd, setRealtimeCmd] = useState(null);
  const [deleting, setDeleting] = useState(false);
  const { subscribe, connected } = useStomp();

  // Tải thông tin thiết bị
  useEffect(() => {
    (async () => {
      setLoading(true);
      try {
        const data = await getDevice(deviceId);
        setDevice(data);
      } catch (err) {
        setError(err.response?.data?.message || 'Không tìm thấy thông tin thiết bị');
      } finally {
        setLoading(false);
      }
    })();
  }, [deviceId]);

  // Lắng nghe telemetry real-time
  useEffect(() => {
    const unsub = subscribe(`/topic/telemetry/${deviceId}`, (msg) => {
      setRealtimeTelemetry(msg);
      setDevice((prev) =>
        prev
          ? {
              ...prev,
              latestTelemetry: {
                ...prev.latestTelemetry,
                temp: msg.temp,
                hum: msg.hum,
                recordedAt: msg.recordedAt || new Date().toISOString(),
              },
              lastSeen: msg.recordedAt || new Date().toISOString(),
            }
          : prev
      );
    });
    return unsub;
  }, [subscribe, deviceId]);

  // Lắng nghe cập nhật lệnh real-time
  useEffect(() => {
    const unsub = subscribe(`/topic/commands/${deviceId}`, (msg) => {
      setRealtimeCmd(msg);
    });
    return unsub;
  }, [subscribe, deviceId]);

  // Lắng nghe online/offline real-time
  useEffect(() => {
    const unsub = subscribe('/topic/devices', (msg) => {
      if (msg.deviceId === deviceId) {
        setDevice((prev) => (prev ? { ...prev, online: msg.online } : prev));
      }
    });
    return unsub;
  }, [subscribe, deviceId]);

  // Xóa thiết bị
  const handleDelete = async () => {
    if (
      !window.confirm(
        `Xác nhận xóa thiết bị "${deviceId}"? Toàn bộ dữ liệu telemetry và lịch sử lệnh sẽ bị xóa vĩnh viễn.`
      )
    )
      return;

    setDeleting(true);
    try {
      await deleteDevice(deviceId);
      toast.success('Đã xóa thiết bị thành công');
      navigate('/devices');
    } catch (err) {
      toast.error(err.response?.data?.message || 'Lỗi khi xóa thiết bị');
      setDeleting(false);
    }
  };

  if (loading) {
    return (
      <div className="loading-state">
        <div className="loading-spinner" />
        <p style={{ color: 'var(--ha-text-muted)' }}>Đang tải dữ liệu thực thể...</p>
      </div>
    );
  }

  if (error || !device) {
    return (
      <div className="error-state">
        <p style={{ color: 'var(--ha-danger)', fontWeight: 600 }}>⚠️ {error || 'Không tìm thấy thiết bị'}</p>
        <button className="btn btn-ghost" onClick={() => navigate('/devices')} style={{ marginTop: '0.75rem' }}>
          <ArrowLeft size={16} /> Quay lại danh sách
        </button>
      </div>
    );
  }

  const isActuator = device.type === 'actuator';

  return (
    <div className="fade-in" style={{ display: 'flex', flexDirection: 'column', gap: '1.75rem' }}>
      {/* 1. Header thực thể kiểu Home Assistant */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          flexWrap: 'wrap',
          gap: '1rem',
          paddingBottom: '1rem',
          borderBottom: '1px solid var(--ha-border)',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
          <button
            className="btn btn-ghost"
            onClick={() => navigate('/devices')}
            id="btn-back-devices"
            style={{ padding: '0.5rem', borderRadius: '50%' }}
            title="Quay lại danh sách"
          >
            <ArrowLeft size={18} />
          </button>

          <div
            className={`ha-icon-badge ${isActuator ? 'actuator' : 'sensor'}`}
            style={{ width: 50, height: 50 }}
          >
            {isActuator ? <Zap size={26} /> : <Cpu size={26} />}
          </div>

          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', flexWrap: 'wrap' }}>
              <h2 style={{ fontSize: '1.5rem', fontWeight: 700, letterSpacing: '-0.02em' }}>
                {device.name}
              </h2>
              <OnlineBadge online={device.online} />
              <DeviceTypeBadge type={device.type} />
            </div>
            <div style={{ fontSize: '0.825rem', color: 'var(--ha-text-muted)', fontFamily: 'monospace', marginTop: '0.2rem' }}>
              ID: {device.deviceId} • Firmware: {device.fwVersion || '1.0.0'}
            </div>
          </div>
        </div>

        <div>
          <button
            className="btn btn-danger"
            onClick={handleDelete}
            disabled={deleting}
            id="btn-delete-device"
          >
            <Trash2 size={16} />
            {deleting ? 'Đang xóa...' : 'Xóa thiết bị'}
          </button>
        </div>
      </div>

      {/* 2. Hàng chỉ số Lovelace Metric Cards */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
          gap: '1rem',
        }}
      >
        <div className="ha-metric-box" style={{ padding: '1rem 1.15rem' }}>
          <div className="ha-metric-icon temp" style={{ width: 42, height: 42 }}>
            <Thermometer size={22} />
          </div>
          <div className="ha-metric-info">
            <span className="ha-metric-label">Nhiệt độ hiện tại</span>
            <span className="ha-metric-val temp-text" style={{ fontSize: '1.5rem' }}>
              {device.latestTelemetry?.temp != null
                ? `${device.latestTelemetry.temp.toFixed(1)}°C`
                : '—'}
            </span>
          </div>
        </div>

        <div className="ha-metric-box" style={{ padding: '1rem 1.15rem' }}>
          <div className="ha-metric-icon hum" style={{ width: 42, height: 42 }}>
            <Droplets size={22} />
          </div>
          <div className="ha-metric-info">
            <span className="ha-metric-label">Độ ẩm hiện tại</span>
            <span className="ha-metric-val hum-text" style={{ fontSize: '1.5rem' }}>
              {device.latestTelemetry?.hum != null
                ? `${device.latestTelemetry.hum.toFixed(1)}%`
                : '—'}
            </span>
          </div>
        </div>

        <div className="ha-metric-box" style={{ padding: '1rem 1.15rem' }}>
          <div
            className="ha-metric-icon"
            style={{ width: 42, height: 42, background: 'rgba(16, 185, 129, 0.12)', color: '#10b981' }}
          >
            <Tag size={20} />
          </div>
          <div className="ha-metric-info">
            <span className="ha-metric-label">Trạng thái đăng ký</span>
            <span className="ha-metric-val" style={{ fontSize: '1.15rem', color: '#10b981' }}>
              {device.regStatus || 'ACTIVE'}
            </span>
          </div>
        </div>

        <div className="ha-metric-box" style={{ padding: '1rem 1.15rem' }}>
          <div
            className="ha-metric-icon"
            style={{ width: 42, height: 42, background: 'rgba(148, 163, 184, 0.12)', color: '#94a3b8' }}
          >
            <Clock size={20} />
          </div>
          <div className="ha-metric-info">
            <span className="ha-metric-label">Cập nhật lần cuối</span>
            <span style={{ fontSize: '0.875rem', fontWeight: 600, color: 'var(--ha-text-secondary)', marginTop: 2 }}>
              {formatTime(device.lastSeen)}
            </span>
          </div>
        </div>
      </div>

      {/* 3. Bố cục chính: Biểu đồ & Điều khiển thực thể */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: isActuator ? 'repeat(auto-fit, minmax(480px, 1fr))' : '1fr',
          gap: '1.5rem',
          alignItems: 'start',
        }}
      >
        {/* Biểu đồ Recharts AreaChart */}
        <div>
          <TelemetryChart deviceId={deviceId} realtimeData={realtimeTelemetry} />
        </div>

        {/* Bảng điều khiển công tắc & Hoạt động */}
        <div>
          <CommandPanel
            deviceId={deviceId}
            online={device.online}
            type={device.type}
            realtimeCmd={realtimeCmd}
          />
        </div>
      </div>
    </div>
  );
}
