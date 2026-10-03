/**
 * Trang Login – đăng nhập admin, lưu JWT.
 * Đặc tả mục 10: Form đăng nhập, lưu JWT, chuyển hướng.
 */
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { login } from '../api/client';
import { Home } from 'lucide-react';

export default function Login() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      await login(username, password);
      navigate('/devices');
    } catch (err) {
      const msg = err.response?.data?.message || 'Sai tài khoản hoặc mật khẩu';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-page">
      <div className="card login-box fade-in">
        <div className="login-logo">
          <div className="login-logo-icon">
            <Home size={28} color="white" />
          </div>
          <h1>Smart Home</h1>
          <p>Đăng nhập để quản lý hệ thống IoT</p>
        </div>

        {error && <div className="login-error" id="login-error">{error}</div>}

        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label className="form-label" htmlFor="login-username">Tên đăng nhập</label>
            <input
              id="login-username"
              className="form-input"
              type="text"
              placeholder="admin"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              required
            />
          </div>
          <div className="form-group">
            <label className="form-label" htmlFor="login-password">Mật khẩu</label>
            <input
              id="login-password"
              className="form-input"
              type="password"
              placeholder="••••••••"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              required
            />
          </div>
          <button
            type="submit"
            className="btn btn-primary btn-lg btn-block"
            disabled={loading}
            id="btn-login"
          >
            {loading ? 'Đang đăng nhập...' : 'Đăng nhập'}
          </button>
        </form>
      </div>
    </div>
  );
}
