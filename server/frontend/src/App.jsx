/**
 * App – Layout chính + Protected Routes phong cách Home Assistant Frontend.
 * Tích hợp thanh điều hướng Lovelace, hiển thị trạng thái kết nối STOMP WebSocket.
 */
import { BrowserRouter, Routes, Route, Navigate, NavLink, useNavigate } from 'react-router-dom';
import { isLoggedIn, logout } from './api/client';
import { Toaster } from 'react-hot-toast';
import Login from './pages/Login';
import Devices from './pages/Devices';
import DeviceDetail from './pages/DeviceDetail';
import RegisterDevice from './pages/RegisterDevice';
import useStomp from './hooks/useStomp';
import { Home, LogOut, LayoutGrid, PlusCircle, Activity } from 'lucide-react';

// Guard: chuyển về /login nếu chưa đăng nhập
function ProtectedRoute({ children }) {
  if (!isLoggedIn()) {
    return <Navigate to="/login" replace />;
  }
  return children;
}

// Header Lovelace
function AppHeader() {
  const navigate = useNavigate();
  const { connected } = useStomp();

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <header className="app-header">
      <div className="header-inner">
        {/* Brand */}
        <div className="header-brand" onClick={() => navigate('/devices')}>
          <div className="header-brand-icon">
            <Home size={20} />
          </div>
          <div>
            <h1>
              Smart Home
              <span className="ha-badge">Lovelace</span>
            </h1>
          </div>
        </div>

        {/* Home Assistant Quick Status Badges */}
        <div className="header-badges">
          <div className={`lovelace-badge ${connected ? 'live-pulse' : ''}`}>
            <span className={connected ? 'pulse-dot' : 'ha-status-dot offline'} />
            <span>{connected ? 'Live WebSocket' : 'Mất kết nối WS'}</span>
          </div>
          <div className="lovelace-badge">
            <span>📡 {window.location.hostname}</span>
          </div>
        </div>

        {/* Navigation Tabs */}
        <nav className="header-nav">
          <NavLink
            to="/devices"
            id="nav-devices"
            className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}
          >
            <LayoutGrid size={16} />
            <span>Tổng quan</span>
          </NavLink>

          <NavLink
            to="/register"
            id="nav-register"
            className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}
          >
            <PlusCircle size={16} />
            <span>Thêm thiết bị</span>
          </NavLink>

          <button className="btn-logout" onClick={handleLogout} id="btn-header-logout" title="Đăng xuất khỏi hệ thống">
            <LogOut size={16} />
            <span>Đăng xuất</span>
          </button>
        </nav>
      </div>
    </header>
  );
}

// Layout cho trang đã đăng nhập
function AuthLayout({ children }) {
  return (
    <div className="app-layout">
      <AppHeader />
      <main className="main-content">
        {children}
      </main>
    </div>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      {/* Toast notifications */}
      <Toaster
        position="top-right"
        toastOptions={{
          style: {
            background: 'rgba(22, 28, 40, 0.95)',
            color: '#f8fafc',
            border: '1px solid rgba(255, 255, 255, 0.12)',
            borderRadius: '12px',
            backdropFilter: 'blur(10px)',
            fontSize: '0.875rem',
            boxShadow: '0 10px 30px rgba(0, 0, 0, 0.5)',
          },
          success: {
            iconTheme: { primary: '#10b981', secondary: '#ffffff' },
          },
          error: {
            iconTheme: { primary: '#ef4444', secondary: '#ffffff' },
          },
        }}
      />

      <Routes>
        {/* Public route */}
        <Route path="/login" element={<Login />} />

        {/* Protected routes */}
        <Route
          path="/devices"
          element={
            <ProtectedRoute>
              <AuthLayout>
                <Devices />
              </AuthLayout>
            </ProtectedRoute>
          }
        />
        <Route
          path="/devices/:deviceId"
          element={
            <ProtectedRoute>
              <AuthLayout>
                <DeviceDetail />
              </AuthLayout>
            </ProtectedRoute>
          }
        />
        <Route
          path="/register"
          element={
            <ProtectedRoute>
              <AuthLayout>
                <RegisterDevice />
              </AuthLayout>
            </ProtectedRoute>
          }
        />

        {/* Fallback */}
        <Route path="*" element={<Navigate to="/devices" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
