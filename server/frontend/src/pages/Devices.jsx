/**
 * Trang Devices – Tổng quan thực thể phong cách Home Assistant Lovelace Overview.
 * Bao gồm thanh Lovelace Quick Badges, tóm tắt môi trường thời gian thực và lưới Tile Cards.
 */
import { useState, useEffect, useMemo } from 'react';
import { getDevices } from '../api/client';
import DeviceCard from '../components/DeviceCard';
import useStomp from '../hooks/useStomp';
import { useNavigate } from 'react-router-dom';
import {
  Plus, RefreshCw, Thermometer, Droplets, Wifi, LayoutGrid, CheckCircle2,
} from 'lucide-react';

export default function Devices() {
  const [devices, setDevices] = useState([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState('');
  const { subscribe, connected } = useStomp();
  const navigate = useNavigate();

  // Tải danh sách thiết bị
  const fetchDevices = async (isManual = false) => {
    if (isManual) setRefreshing(true);
    else setLoading(true);
    setError('');
    try {
      const data = await getDevices();
      setDevices(Array.isArray(data) ? data : []);
    } catch (err) {
      setError(err.response?.data?.message || 'Không thể tải danh sách thiết bị');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => { fetchDevices(); }, []);

  // Lắng nghe thay đổi trạng thái online/offline real-time
  useEffect(() => {
    const unsub = subscribe('/topic/devices', (msg) => {
      setDevices((prev) =>
        prev.map((d) => (d.deviceId === msg.deviceId ? { ...d, ...msg } : d))
      );
    });
    return unsub;
  }, [subscribe]);

  // Lắng nghe telemetry real-time của từng node để cập nhật nhiệt độ/độ ẩm ngay trên card
  useEffect(() => {
    // Đăng ký telemetry cho tất cả các device đang có trong state
    const unsubs = devices.map((dev) =>
      subscribe(`/topic/telemetry/${dev.deviceId}`, (telemetry) => {
        setDevices((prev) =>
          prev.map((d) =>
            d.deviceId === dev.deviceId
              ? {
                  ...d,
                  latestTelemetry: {
                    ...d.latestTelemetry,
                    temp: telemetry.temp,
                    hum: telemetry.hum,
                    recordedAt: telemetry.recordedAt || new Date().toISOString(),
                  },
                  lastSeen: telemetry.recordedAt || new Date().toISOString(),
                }
              : d
          )
        );
      })
    );
    return () => {
      unsubs.forEach((unsub) => unsub && unsub());
    };
  }, [devices.map((d) => d.deviceId).join(','), subscribe]);

  // Tính toán tóm tắt Lovelace
  const summary = useMemo(() => {
    const onlineCount = devices.filter((d) => d.online).length;
    const tempReadings = devices
      .map((d) => d.latestTelemetry?.temp)
      .filter((t) => t != null);
    const humReadings = devices
      .map((d) => d.latestTelemetry?.hum)
      .filter((h) => h != null);

    const avgTemp =
      tempReadings.length > 0
        ? (tempReadings.reduce((a, b) => a + b, 0) / tempReadings.length).toFixed(1)
        : null;
    const avgHum =
      humReadings.length > 0
        ? (humReadings.reduce((a, b) => a + b, 0) / humReadings.length).toFixed(1)
        : null;

    return {
      total: devices.length,
      online: onlineCount,
      avgTemp,
      avgHum,
    };
  }, [devices]);

  const [filterMode, setFilterMode] = useState('all');

  // Lọc thiết bị theo filterMode
  const filteredDevices = useMemo(() => {
    if (filterMode === 'actuator') return devices.filter((d) => d.type === 'actuator');
    if (filterMode === 'sensor') return devices.filter((d) => d.type === 'sensor');
    if (filterMode === 'online') return devices.filter((d) => d.online);
    return devices;
  }, [devices, filterMode]);

  // Nhóm thiết bị theo section khi ở chế độ 'all'
  const actuatorDevices = useMemo(() => devices.filter((d) => d.type === 'actuator'), [devices]);
  const sensorDevices = useMemo(() => devices.filter((d) => d.type === 'sensor'), [devices]);

  return (
    <div className="fade-in">
      {/* 1. Thanh Lovelace Quick Badges trên đầu trang */}
      <div
        style={{
          display: 'flex',
          flexWrap: 'wrap',
          gap: '0.65rem',
          marginBottom: '1.5rem',
          alignItems: 'center',
        }}
      >
        <div className="lovelace-badge">
          <Wifi size={14} color={summary.online > 0 ? '#10b981' : '#64748b'} />
          <span>
            {summary.online} / {summary.total} Trực tuyến
          </span>
        </div>

        {summary.avgTemp && (
          <div className="lovelace-badge" style={{ borderColor: 'rgba(249, 115, 22, 0.3)', color: '#fdba74' }}>
            <Thermometer size={14} color="#f97316" />
            <span>Nhiệt độ TB: {summary.avgTemp}°C</span>
          </div>
        )}

        {summary.avgHum && (
          <div className="lovelace-badge" style={{ borderColor: 'rgba(6, 182, 212, 0.3)', color: '#67e8f9' }}>
            <Droplets size={14} color="#06b6d4" />
            <span>Độ ẩm TB: {summary.avgHum}%</span>
          </div>
        )}

        <div className="lovelace-badge">
          <CheckCircle2 size={14} color="#38bdf8" />
          <span>Hạ tầng: 2 Mosquitto + TLS</span>
        </div>
      </div>

      {/* 2. Tiêu đề khu vực & Các nút hành động */}
      <div className="ha-section-header">
        <div className="ha-section-title">
          <LayoutGrid size={24} color="#03a9f4" />
          <div>
            <h2>Bảng điều khiển Lovelace (Entities)</h2>
            <p>
              Giám sát cảm biến DHT & Điều khiển Relay/LED thời gian thực qua giao thức MQTT TLS.
            </p>
          </div>
        </div>

        <div className="ha-header-actions">
          <button
            className="btn btn-ghost"
            onClick={() => fetchDevices(true)}
            disabled={refreshing}
            id="btn-refresh-devices"
            title="Tải lại danh sách thiết bị"
          >
            <RefreshCw size={15} className={refreshing ? 'loading-spinner' : ''} />
            Làm mới
          </button>
          <button
            className="btn btn-primary"
            onClick={() => navigate('/register')}
            id="btn-goto-register"
          >
            <Plus size={16} /> Thêm thiết bị
          </button>
        </div>
      </div>

      {/* 3. Thanh bộ lọc góc nhìn Lovelace (Filter Chips) */}
      <div className="ha-filter-bar">
        <button
          type="button"
          className={`ha-filter-chip ${filterMode === 'all' ? 'active' : ''}`}
          onClick={() => setFilterMode('all')}
        >
          <span>Tất cả thiết bị</span>
          <span className="ha-chip-count">{devices.length}</span>
        </button>

        <button
          type="button"
          className={`ha-filter-chip ${filterMode === 'actuator' ? 'active' : ''}`}
          onClick={() => setFilterMode('actuator')}
        >
          <span>⚡ Điều khiển (Actuator)</span>
          <span className="ha-chip-count">{actuatorDevices.length}</span>
        </button>

        <button
          type="button"
          className={`ha-filter-chip ${filterMode === 'sensor' ? 'active' : ''}`}
          onClick={() => setFilterMode('sensor')}
        >
          <span>🌡️ Cảm biến (Sensor)</span>
          <span className="ha-chip-count">{sensorDevices.length}</span>
        </button>

        <button
          type="button"
          className={`ha-filter-chip ${filterMode === 'online' ? 'active' : ''}`}
          onClick={() => setFilterMode('online')}
        >
          <span>🟢 Đang trực tuyến</span>
          <span className="ha-chip-count">{summary.online}</span>
        </button>
      </div>

      {/* 4. Nội dung theo Lovelace Sections Layout */}
      {loading ? (
        <div className="loading-state">
          <div className="loading-spinner" />
          <p style={{ color: 'var(--ha-text-muted)' }}>Đang đồng bộ thiết bị từ máy chủ...</p>
        </div>
      ) : error ? (
        <div className="error-state">
          <p style={{ color: 'var(--ha-danger)', fontWeight: 600 }}>⚠️ {error}</p>
          <button className="btn btn-ghost" onClick={() => fetchDevices(false)} style={{ marginTop: '0.75rem' }}>
            Thử lại
          </button>
        </div>
      ) : devices.length === 0 ? (
        <div className="empty-state">
          <LayoutGrid size={48} color="#64748b" />
          <h3 style={{ color: 'var(--ha-text-primary)' }}>Chưa có thiết bị nào</h3>
          <p style={{ color: 'var(--ha-text-secondary)', maxWidth: 420 }}>
            Bạn chưa đăng ký thiết bị ESP32 nào trong hệ thống. Hãy bấm nút Thêm thiết bị bên dưới để bắt đầu.
          </p>
          <button className="btn btn-primary" onClick={() => navigate('/register')} style={{ marginTop: '0.5rem' }}>
            <Plus size={16} /> Đăng ký thiết bị đầu tiên
          </button>
        </div>
      ) : filterMode === 'all' ? (
        /* Bố cục Sections chuẩn Lovelace */
        <div>
          {/* Section: Thiết bị điều khiển (Actuators) */}
          {actuatorDevices.length > 0 && (
            <div className="ha-section-block">
              <div className="ha-section-label">
                <span>⚡ Thiết bị điều khiển & Chấp hành</span>
                <span className="ha-section-badge">{actuatorDevices.length} thiết bị</span>
              </div>
              <div className="ha-tiles-grid">
                {actuatorDevices.map((device) => (
                  <DeviceCard key={device.deviceId} device={device} />
                ))}
              </div>
            </div>
          )}

          {/* Section: Cảm biến quan trắc (Sensors) */}
          {sensorDevices.length > 0 && (
            <div className="ha-section-block">
              <div className="ha-section-label">
                <span>🌡️ Trạm quan trắc cảm biến môi trường</span>
                <span className="ha-section-badge">{sensorDevices.length} thiết bị</span>
              </div>
              <div className="ha-tiles-grid">
                {sensorDevices.map((device) => (
                  <DeviceCard key={device.deviceId} device={device} />
                ))}
              </div>
            </div>
          )}
        </div>
      ) : (
        /* Grid khi lọc theo chip */
        <div className="ha-tiles-grid">
          {filteredDevices.map((device) => (
            <DeviceCard key={device.deviceId} device={device} />
          ))}
        </div>
      )}
    </div>
  );
}
