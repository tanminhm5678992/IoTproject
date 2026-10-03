/**
 * TelemetryChart – Biểu đồ lịch sử môi trường phong cách Home Assistant History Graph.
 * Sử dụng AreaChart với gradient mượt mà, bộ lọc 1h/6h/24h và tooltip kính mờ.
 */
import { useState, useEffect, useCallback } from 'react';
import {
  ResponsiveContainer, AreaChart, Area, XAxis, YAxis,
  CartesianGrid, Tooltip, Legend,
} from 'recharts';
import { getTelemetry } from '../api/client';
import { Activity, Clock } from 'lucide-react';

function formatXAxis(ts) {
  const d = new Date(ts);
  return d.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' });
}

function CustomTooltip({ active, payload, label }) {
  if (!active || !payload?.length) return null;
  const d = new Date(label);
  return (
    <div style={{
      background: 'rgba(22, 28, 40, 0.95)',
      border: '1px solid rgba(255, 255, 255, 0.12)',
      borderRadius: '12px',
      padding: '0.85rem 1.15rem',
      boxShadow: '0 8px 24px rgba(0, 0, 0, 0.5)',
      backdropFilter: 'blur(10px)',
      fontSize: '0.825rem',
    }}>
      <div style={{ color: '#94a3b8', marginBottom: 6, fontWeight: 500 }}>
        🕒 {d.toLocaleString('vi-VN')}
      </div>
      {payload.map((p) => (
        <div key={p.dataKey} style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', margin: '3px 0' }}>
          <span style={{ width: 8, height: 8, borderRadius: '50%', background: p.stroke }} />
          <span style={{ color: '#cbd5e1' }}>{p.name}:</span>
          <strong style={{ color: p.stroke }}>
            {p.value != null ? p.value.toFixed(1) : '—'}
            {p.dataKey === 'temp' ? ' °C' : ' %'}
          </strong>
        </div>
      ))}
    </div>
  );
}

export default function TelemetryChart({ deviceId, realtimeData }) {
  const [range, setRange] = useState('1h');
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(true);

  // Tải dữ liệu từ API theo khoảng thời gian
  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      const now = new Date();
      const from = new Date(now);
      if (range === '1h') from.setHours(from.getHours() - 1);
      else if (range === '6h') from.setHours(from.getHours() - 6);
      else from.setHours(from.getHours() - 24);

      const result = await getTelemetry(deviceId, {
        from: from.toISOString(),
        to: now.toISOString(),
        limit: 500,
        page: 0,
      });

      const items = (result.items || []).reverse().map((t) => ({
        time: new Date(t.recordedAt).getTime(),
        temp: t.temp,
        hum: t.hum,
      }));
      setData(items);
    } catch (err) {
      console.error('[TelemetryChart] Lỗi tải dữ liệu:', err);
    } finally {
      setLoading(false);
    }
  }, [deviceId, range]);

  useEffect(() => { fetchData(); }, [fetchData]);

  // Cập nhật real-time khi có bản tin telemetry mới
  useEffect(() => {
    if (!realtimeData) return;
    setData((prev) => {
      const point = {
        time: new Date(realtimeData.recordedAt || Date.now()).getTime(),
        temp: realtimeData.temp,
        hum: realtimeData.hum,
      };
      const next = [...prev, point];
      if (next.length > 600) return next.slice(next.length - 600);
      return next;
    });
  }, [realtimeData]);

  // Thống kê Min / Max / Current theo phong cách Home Assistant Sensor Statistics
  const stats = (() => {
    if (!data.length) return null;
    const temps = data.map((d) => d.temp).filter((v) => v != null);
    const hums = data.map((d) => d.hum).filter((v) => v != null);

    const latest = data[data.length - 1];
    return {
      currentTemp: latest?.temp,
      maxTemp: temps.length ? Math.max(...temps) : null,
      minTemp: temps.length ? Math.min(...temps) : null,
      currentHum: latest?.hum,
      maxHum: hums.length ? Math.max(...hums) : null,
      minHum: hums.length ? Math.min(...hums) : null,
    };
  })();

  return (
    <div className="ha-logbook-card">
      <div className="ha-card-header">
        <h3>
          <Activity size={18} color="#f97316" />
          Lịch sử cảm biến (Sensor History)
        </h3>
        {/* Nút lọc thời gian phong cách Home Assistant pills */}
        <div style={{ display: 'flex', gap: '0.25rem', background: 'var(--ha-surface)', padding: 3, borderRadius: 'var(--ha-radius-pill)', border: '1px solid var(--ha-border)' }}>
          {['1h', '6h', '24h'].map((r) => (
            <button
              key={r}
              onClick={() => setRange(r)}
              style={{
                border: 'none',
                background: range === r ? 'var(--ha-primary)' : 'transparent',
                color: range === r ? 'white' : 'var(--ha-text-secondary)',
                padding: '0.25rem 0.75rem',
                borderRadius: 'var(--ha-radius-pill)',
                fontSize: '0.75rem',
                fontWeight: 600,
                cursor: 'pointer',
                transition: 'var(--ha-transition)',
              }}
            >
              {r === '1h' ? '1 giờ' : r === '6h' ? '6 giờ' : '24 giờ'}
            </button>
          ))}
        </div>
      </div>

      <div style={{ padding: '1.25rem' }}>
        {/* Home Assistant Sensor Statistics Bar */}
        {stats && (
          <div className="ha-stat-summary-bar">
            <div className="ha-stat-chip">
              <span className="ha-stat-chip-label" style={{ color: '#fdba74' }}>Nhiệt độ hiện tại</span>
              <span className="ha-stat-chip-value" style={{ color: '#f97316' }}>
                {stats.currentTemp != null ? `${stats.currentTemp.toFixed(1)}°C` : '—'}
              </span>
            </div>
            <div className="ha-stat-chip">
              <span className="ha-stat-chip-label">Nhiệt độ Max / Min</span>
              <span className="ha-stat-chip-value" style={{ fontSize: '0.95rem' }}>
                {stats.maxTemp != null ? `${stats.maxTemp.toFixed(1)}°` : '—'} / {stats.minTemp != null ? `${stats.minTemp.toFixed(1)}°` : '—'}
              </span>
            </div>
            <div className="ha-stat-chip">
              <span className="ha-stat-chip-label" style={{ color: '#67e8f9' }}>Độ ẩm hiện tại</span>
              <span className="ha-stat-chip-value" style={{ color: '#06b6d4' }}>
                {stats.currentHum != null ? `${stats.currentHum.toFixed(1)}%` : '—'}
              </span>
            </div>
            <div className="ha-stat-chip">
              <span className="ha-stat-chip-label">Độ ẩm Max / Min</span>
              <span className="ha-stat-chip-value" style={{ fontSize: '0.95rem' }}>
                {stats.maxHum != null ? `${stats.maxHum.toFixed(1)}%` : '—'} / {stats.minHum != null ? `${stats.minHum.toFixed(1)}%` : '—'}
              </span>
            </div>
          </div>
        )}

        {loading ? (
          <div className="loading-state" style={{ padding: '3rem 1rem' }}>
            <div className="loading-spinner" />
            <p style={{ color: 'var(--ha-text-muted)', fontSize: '0.85rem' }}>Đang nạp dữ liệu lịch sử...</p>
          </div>
        ) : data.length === 0 ? (
          <div className="empty-state" style={{ padding: '3rem 1rem' }}>
            <Clock size={36} color="#64748b" />
            <p style={{ color: 'var(--ha-text-secondary)' }}>Chưa có dữ liệu đo nào trong khoảng thời gian này</p>
          </div>
        ) : (
          <div style={{ width: '100%', height: 280 }}>
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={data} margin={{ top: 12, right: 12, left: -10, bottom: 0 }}>
                <defs>
                  <linearGradient id="tempGradient" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="#f97316" stopOpacity={0.35} />
                    <stop offset="95%" stopColor="#f97316" stopOpacity={0.0} />
                  </linearGradient>
                  <linearGradient id="humGradient" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="#06b6d4" stopOpacity={0.3} />
                    <stop offset="95%" stopColor="#06b6d4" stopOpacity={0.0} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.05)" vertical={false} />
                <XAxis
                  dataKey="time"
                  tickFormatter={formatXAxis}
                  stroke="#64748b"
                  fontSize={11}
                  tickLine={false}
                  axisLine={false}
                />
                <YAxis
                  yAxisId="temp"
                  orientation="left"
                  stroke="#f97316"
                  fontSize={11}
                  tickLine={false}
                  axisLine={false}
                  domain={['auto', 'auto']}
                  tickFormatter={(v) => `${v}°`}
                />
                <YAxis
                  yAxisId="hum"
                  orientation="right"
                  stroke="#06b6d4"
                  fontSize={11}
                  tickLine={false}
                  axisLine={false}
                  domain={[0, 100]}
                  tickFormatter={(v) => `${v}%`}
                />
                <Tooltip content={<CustomTooltip />} />
                <Legend wrapperStyle={{ fontSize: '0.8rem', paddingTop: '0.75rem' }} />
                <Area
                  yAxisId="temp"
                  type="monotone"
                  dataKey="temp"
                  name="Nhiệt độ (°C)"
                  stroke="#f97316"
                  strokeWidth={2.5}
                  fillOpacity={1}
                  fill="url(#tempGradient)"
                />
                <Area
                  yAxisId="hum"
                  type="monotone"
                  dataKey="hum"
                  name="Độ ẩm (%)"
                  stroke="#06b6d4"
                  strokeWidth={2}
                  fillOpacity={1}
                  fill="url(#humGradient)"
                />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        )}
      </div>
    </div>
  );
}
