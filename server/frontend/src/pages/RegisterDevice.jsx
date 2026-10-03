/**
 * Trang RegisterDevice – đăng ký thiết bị mới.
 * Đặc tả mục 10: Form deviceId, name, type; sau khi tạo hiển thị regToken + hướng dẫn nạp firmware.
 */
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { createDevice } from '../api/client';
import { PlusCircle, ArrowLeft, Copy, Check } from 'lucide-react';
import toast from 'react-hot-toast';

export default function RegisterDevice() {
  const [deviceId, setDeviceId] = useState('');
  const [name, setName] = useState('');
  const [type, setType] = useState('sensor');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState(null);
  const [copied, setCopied] = useState(false);
  const navigate = useNavigate();

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const data = await createDevice({ deviceId, name, type });
      setResult(data);
      toast.success('Đăng ký thiết bị thành công!');
    } catch (err) {
      const msg = err.response?.data?.message || 'Lỗi đăng ký thiết bị';
      setError(msg);
      toast.error(msg);
    } finally {
      setLoading(false);
    }
  };

  const handleCopy = () => {
    if (result?.regToken) {
      navigator.clipboard.writeText(result.regToken);
      setCopied(true);
      toast.success('Đã sao chép regToken');
      setTimeout(() => setCopied(false), 2000);
    }
  };

  return (
    <div className="fade-in" style={{ maxWidth: 560, margin: '0 auto' }}>
      <div className="page-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
          <button className="btn btn-ghost btn-sm" onClick={() => navigate('/devices')}>
            <ArrowLeft size={16} />
          </button>
          <h1>Thêm thiết bị mới</h1>
        </div>
      </div>

      <div className="card">
        <div className="card-body">
          {error && <div className="login-error">{error}</div>}

          {!result ? (
            <form onSubmit={handleSubmit}>
              <div className="form-group">
                <label className="form-label" htmlFor="reg-device-id">Device ID</label>
                <input
                  id="reg-device-id"
                  className="form-input"
                  type="text"
                  placeholder="node1"
                  value={deviceId}
                  onChange={(e) => setDeviceId(e.target.value)}
                  required
                  pattern="[a-zA-Z0-9_-]+"
                  title="Chỉ chữ cái, số, dấu gạch ngang và gạch dưới"
                />
              </div>

              <div className="form-group">
                <label className="form-label" htmlFor="reg-name">Tên thiết bị</label>
                <input
                  id="reg-name"
                  className="form-input"
                  type="text"
                  placeholder="Cảm biến phòng khách"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  required
                />
              </div>

              <div className="form-group">
                <label className="form-label" htmlFor="reg-type">Loại thiết bị</label>
                <select
                  id="reg-type"
                  className="form-select"
                  value={type}
                  onChange={(e) => setType(e.target.value)}
                >
                  <option value="sensor">sensor – Cảm biến (chỉ đo)</option>
                  <option value="actuator">actuator – Điều khiển (relay + LED)</option>
                </select>
              </div>

              <button
                type="submit"
                className="btn btn-primary btn-lg btn-block"
                disabled={loading}
                id="btn-register-device"
              >
                <PlusCircle size={18} />
                {loading ? 'Đang đăng ký...' : 'Đăng ký thiết bị'}
              </button>
            </form>
          ) : (
            <div className="register-result">
              <h3>✅ Đăng ký thành công!</h3>

              <div style={{ marginBottom: '1rem' }}>
                <div className="form-label">Device ID</div>
                <div style={{ color: 'var(--text-accent)', fontFamily: 'monospace', fontWeight: 600 }}>
                  {result.deviceId}
                </div>
              </div>

              <div style={{ marginBottom: '1rem' }}>
                <div className="form-label" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                  <span>Registration Token</span>
                  <button
                    className="btn btn-ghost btn-sm"
                    onClick={handleCopy}
                    style={{ padding: '0.25rem 0.5rem', fontSize: '0.75rem' }}
                  >
                    {copied ? <><Check size={12} /> Đã sao chép</> : <><Copy size={12} /> Sao chép</>}
                  </button>
                </div>
                <div className="token-display" id="reg-token-display">
                  {result.regToken}
                </div>
              </div>

              {result.mqttTopics && (
                <div style={{ marginBottom: '1rem' }}>
                  <div className="form-label">MQTT Topics</div>
                  <div style={{ fontSize: '0.8125rem', color: 'var(--text-secondary)', fontFamily: 'monospace', lineHeight: 1.8 }}>
                    {Object.entries(result.mqttTopics).map(([key, val]) => (
                      <div key={key}>
                        <span style={{ color: 'var(--text-muted)' }}>{key}:</span> {val}
                      </div>
                    ))}
                  </div>
                </div>
              )}

              <div className="hint">
                <strong>Bước tiếp theo:</strong><br />
                1. Sao chép <code>regToken</code> ở trên vào file <code>secrets.h</code> của firmware.<br />
                2. Nạp firmware lên ESP32-C3 bằng Arduino IDE.<br />
                3. Thiết bị sẽ tự gửi <code>register</code> lên broker, trạng thái chuyển thành <strong>ACTIVE</strong>.<br />
                4. Hoặc chạy <code>./scripts/add-device.sh {result.deviceId}</code> để tạo tài khoản MQTT.
              </div>

              <div style={{ display: 'flex', gap: '0.5rem', marginTop: '1.25rem' }}>
                <button
                  className="btn btn-primary"
                  onClick={() => navigate(`/devices/${result.deviceId}`)}
                  id="btn-goto-detail"
                >
                  Xem chi tiết thiết bị
                </button>
                <button
                  className="btn btn-ghost"
                  onClick={() => { setResult(null); setDeviceId(''); setName(''); setType('sensor'); }}
                  id="btn-register-another"
                >
                  Đăng ký thêm
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
