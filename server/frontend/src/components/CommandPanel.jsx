/**
 * CommandPanel – Bảng điều khiển thiết bị phong cách Home Assistant Lovelace Switch & Tile Cards.
 * Hỗ trợ điều khiển tương tác một chạm, hiển thị trạng thái phát sáng và Activity Logbook.
 */
import { useState, useEffect, useCallback } from 'react';
import { sendCommand, getCommands } from '../api/client';
import { CommandStatusBadge } from './StatusBadge';
import { Lightbulb, Zap, Clock, CheckCircle2, AlertTriangle, RefreshCw, Send } from 'lucide-react';
import toast from 'react-hot-toast';

const TARGET_CONFIG = [
  { key: 'relay1', label: 'Relay 1', desc: 'Rơ-le cổng 1', isRelay: true },
  { key: 'relay2', label: 'Relay 2', desc: 'Rơ-le cổng 2', isRelay: true },
  { key: 'led1', label: 'LED 1', desc: 'Đèn LED đỏ', isRelay: false },
  { key: 'led2', label: 'LED 2', desc: 'Đèn LED xanh / Cảnh báo', isRelay: false },
];

function formatTime(ts) {
  if (!ts) return '—';
  return new Date(ts).toLocaleString('vi-VN', {
    hour: '2-digit', minute: '2-digit', second: '2-digit',
    day: '2-digit', month: '2-digit',
  });
}

export default function CommandPanel({ deviceId, online, type, realtimeCmd }) {
  const [pendingTargets, setPendingTargets] = useState(new Set());
  const [commands, setCommands] = useState([]);
  const [cmdPage, setCmdPage] = useState(0);
  const [cmdTotal, setCmdTotal] = useState(0);
  const [loadingCmds, setLoadingCmds] = useState(true);

  // Lưu trạng thái suy diễn hiện tại của từng target (ON hay OFF) từ lịch sử lệnh
  const [states, setStates] = useState({
    relay1: false,
    relay2: false,
    led1: false,
    led2: false,
  });

  const CMD_LIMIT = 8;
  const isActuator = type === 'actuator';

  // Tải lịch sử lệnh
  const fetchCommands = useCallback(async () => {
    setLoadingCmds(true);
    try {
      const result = await getCommands(deviceId, { page: cmdPage, limit: CMD_LIMIT });
      const items = result.items || [];
      setCommands(items);
      setCmdTotal(result.total || 0);

      // Cập nhật trạng thái ON/OFF mới nhất từ lệnh thành công gần nhất
      const newStates = { ...states };
      for (const t of ['relay1', 'relay2', 'led1', 'led2']) {
        const lastCmd = items.find((c) => c.target === t && (c.status === 'DONE' || c.status === 'ACKED'));
        if (lastCmd) {
          newStates[t] = lastCmd.value === 'ON';
        }
      }
      setStates(newStates);
    } catch (err) {
      console.error('[CommandPanel] Lỗi tải lệnh:', err);
    } finally {
      setLoadingCmds(false);
    }
  }, [deviceId, cmdPage]);

  useEffect(() => { fetchCommands(); }, [fetchCommands]);

  // Lắng nghe cập nhật lệnh real-time qua WebSocket STOMP
  useEffect(() => {
    if (!realtimeCmd) return;
    setCommands((prev) => {
      const idx = prev.findIndex((c) => c.cmdId === realtimeCmd.cmdId);
      if (idx >= 0) {
        const updated = [...prev];
        updated[idx] = { ...updated[idx], ...realtimeCmd };
        return updated;
      }
      return [realtimeCmd, ...prev].slice(0, CMD_LIMIT);
    });

    // Cập nhật trạng thái target khi lệnh hoàn thành
    if (['DONE', 'ACKED'].includes(realtimeCmd.status)) {
      setStates((prev) => ({
        ...prev,
        [realtimeCmd.target]: realtimeCmd.value === 'ON',
      }));
    }

    // Bỏ trạng thái pending
    if (['DONE', 'ACKED', 'ERROR', 'TIMEOUT'].includes(realtimeCmd.status)) {
      setPendingTargets((prev) => {
        const next = new Set(prev);
        next.delete(realtimeCmd.target);
        return next;
      });

      if (['DONE', 'ACKED'].includes(realtimeCmd.status)) {
        toast.success(`✨ Đã thực thi ${realtimeCmd.target} → ${realtimeCmd.value}`);
      } else if (realtimeCmd.status === 'ERROR') {
        toast.error(`❌ ${realtimeCmd.target} báo lỗi: ${realtimeCmd.reason || 'không rõ'}`);
      } else if (realtimeCmd.status === 'TIMEOUT') {
        toast.error(`⏱️ ${realtimeCmd.target} quá thời gian phản hồi`);
      }
    }
  }, [realtimeCmd]);

  // Gửi lệnh điều khiển
  const handleSendCommand = async (target, value) => {
    try {
      setPendingTargets((prev) => new Set(prev).add(target));
      await sendCommand(deviceId, target, value);
      toast(`📡 Đang gửi lệnh ${target} → ${value}...`, { duration: 2500 });
      fetchCommands();
    } catch (err) {
      setPendingTargets((prev) => {
        const next = new Set(prev);
        next.delete(target);
        return next;
      });
      const msg = err.response?.data?.message || 'Không thể gửi lệnh';
      toast.error(`Lỗi: ${msg}`);
    }
  };

  // Toggle trạng thái (click trực tiếp vào switch)
  const handleToggle = (target) => {
    const currentState = !!states[target];
    const nextValue = currentState ? 'OFF' : 'ON';
    handleSendCommand(target, nextValue);
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
      {/* 1. Lovelace Interactive Actuator Tiles */}
      <div className="ha-logbook-card">
        <div className="ha-card-header">
          <h3>
            <Zap size={18} color="#03a9f4" />
            Điều khiển thực thể (Entities)
          </h3>
          <span style={{ fontSize: '0.78rem', color: online ? 'var(--ha-online)' : 'var(--ha-offline)' }}>
            {online ? '● Sẵn sàng nhận lệnh' : '○ Thiết bị đang Offline'}
          </span>
        </div>

        <div style={{ padding: '1.25rem' }}>
          {!isActuator && (
            <div style={{ padding: '0.75rem', background: 'rgba(245, 158, 11, 0.1)', borderRadius: 'var(--ha-radius-md)', border: '1px solid rgba(245, 158, 11, 0.25)', marginBottom: '1rem', fontSize: '0.825rem', color: '#fbbf24' }}>
              ⚠️ Thiết bị này đăng ký loại <strong>sensor</strong> (chỉ đọc cảm biến). Các lệnh điều khiển có thể bị firmware từ chối.
            </div>
          )}

          <div className="ha-actuators-grid">
            {TARGET_CONFIG.map(({ key, label, desc, isRelay }) => {
              const isOn = !!states[key];
              const isPending = pendingTargets.has(key);
              const disabled = !online || isPending;

              return (
                <div
                  key={key}
                  className={`ha-switch-card ${isOn ? 'is-on' : ''} ${isRelay ? 'is-relay' : ''} ${isPending ? 'is-pending' : ''}`}
                >
                  <div className="ha-switch-header">
                    <div className="ha-switch-title-wrap">
                      <div className={`ha-icon-badge ${isOn ? 'bulb-on' : 'bulb-off'}`}>
                        {isRelay ? <Zap size={20} /> : <Lightbulb size={20} />}
                      </div>
                      <div className="ha-switch-meta">
                        <h4>{label}</h4>
                        <span>{isPending ? 'Đang chuyển...' : isOn ? 'Đang BẬT' : 'Đang TẮT'}</span>
                      </div>
                    </div>

                    {/* Home Assistant Toggle Switch */}
                    <button
                      className={`ha-toggle-btn ${isOn ? 'active' : ''} ${isRelay ? 'is-relay' : ''}`}
                      onClick={() => handleToggle(key)}
                      disabled={disabled}
                      title={`Bấm để ${isOn ? 'TẮT' : 'BẬT'} ${label}`}
                    >
                      <div className="ha-toggle-thumb" />
                    </button>
                  </div>

                  {/* Hai nút thao tác nhanh BẬT / TẮT */}
                  <div className="ha-switch-actions">
                    <button
                      className={`btn-ha-control btn-on ${isOn ? 'active-btn' : ''}`}
                      onClick={() => handleSendCommand(key, 'ON')}
                      disabled={disabled}
                    >
                      BẬT (ON)
                    </button>
                    <button
                      className={`btn-ha-control btn-off ${!isOn ? 'active-btn' : ''}`}
                      onClick={() => handleSendCommand(key, 'OFF')}
                      disabled={disabled}
                    >
                      TẮT (OFF)
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </div>

      {/* 2. Lovelace Activity Logbook (Lịch sử lệnh) */}
      <div className="ha-logbook-card">
        <div className="ha-card-header">
          <h3>
            <Clock size={18} color="#94a3b8" />
            Nhật ký hoạt động (Activity Logbook)
          </h3>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <span style={{ fontSize: '0.8rem', color: 'var(--ha-text-muted)' }}>
              {cmdTotal} hoạt động
            </span>
            <button
              className="btn btn-ghost"
              style={{ padding: '0.3rem 0.6rem', fontSize: '0.78rem' }}
              onClick={fetchCommands}
              title="Làm mới lịch sử"
            >
              <RefreshCw size={13} />
            </button>
          </div>
        </div>

        {loadingCmds ? (
          <div style={{ padding: '2rem', textAlign: 'center', color: 'var(--ha-text-muted)' }}>
            <div className="loading-spinner" style={{ margin: '0 auto 0.5rem', width: 24, height: 24 }} />
            Đang tải nhật ký...
          </div>
        ) : commands.length === 0 ? (
          <div style={{ padding: '2.5rem', textAlign: 'center', color: 'var(--ha-text-muted)', fontSize: '0.875rem' }}>
            Chưa có hoạt động nào được ghi nhận.
          </div>
        ) : (
          <div className="ha-logbook-list">
            {commands.map((cmd) => {
              const isDone = ['DONE', 'ACKED'].includes(cmd.status);
              const isError = cmd.status === 'ERROR';
              const isTimeout = cmd.status === 'TIMEOUT';
              const isPending = cmd.status === 'PENDING' || cmd.status === 'SENT';

              return (
                <div key={cmd.cmdId} className="ha-logbook-item">
                  <div className="ha-logbook-left">
                    <div
                      className="ha-logbook-icon"
                      style={{
                        background: isDone
                          ? 'var(--ha-online-bg)'
                          : isError
                          ? 'var(--ha-danger-bg)'
                          : isPending
                          ? 'rgba(245, 158, 11, 0.15)'
                          : 'rgba(139, 92, 246, 0.15)',
                        color: isDone
                          ? 'var(--ha-online)'
                          : isError
                          ? 'var(--ha-danger)'
                          : isPending
                          ? '#f59e0b'
                          : '#c4b5fd',
                      }}
                    >
                      {isDone ? (
                        <CheckCircle2 size={16} />
                      ) : isError ? (
                        <AlertTriangle size={16} />
                      ) : isPending ? (
                        <Send size={15} />
                      ) : (
                        <Clock size={16} />
                      )}
                    </div>

                    <div className="ha-logbook-info">
                      <h5>
                        {cmd.target?.toUpperCase()} được đặt thành{' '}
                        <strong style={{ color: cmd.value === 'ON' ? '#fbbf24' : '#94a3b8' }}>
                          {cmd.value}
                        </strong>
                      </h5>
                      <span>
                        {formatTime(cmd.sentAt || cmd.createdAt)}
                        {cmd.ackedAt && ` • Phản hồi lúc ${formatTime(cmd.ackedAt)}`}
                      </span>
                    </div>
                  </div>

                  <CommandStatusBadge status={cmd.status} />
                </div>
              );
            })}
          </div>
        )}

        {/* Phân trang */}
        {cmdTotal > CMD_LIMIT && (
          <div style={{ display: 'flex', justifyContent: 'center', gap: '0.5rem', padding: '0.75rem', borderTop: '1px solid var(--ha-border)' }}>
            <button
              className="btn btn-ghost"
              style={{ padding: '0.3rem 0.75rem', fontSize: '0.78rem' }}
              disabled={cmdPage === 0}
              onClick={() => setCmdPage((p) => Math.max(0, p - 1))}
            >
              Trước
            </button>
            <span style={{ fontSize: '0.8rem', color: 'var(--ha-text-muted)', alignSelf: 'center' }}>
              Trang {cmdPage + 1} / {Math.ceil(cmdTotal / CMD_LIMIT)}
            </span>
            <button
              className="btn btn-ghost"
              style={{ padding: '0.3rem 0.75rem', fontSize: '0.78rem' }}
              disabled={(cmdPage + 1) * CMD_LIMIT >= cmdTotal}
              onClick={() => setCmdPage((p) => p + 1)}
            >
              Sau
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
