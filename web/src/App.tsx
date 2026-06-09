import { useEffect } from 'react';
import { BrowserRouter, Routes, Route, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { ThemeProvider } from './context/ThemeContext';
import { ToastProvider } from './context/ToastContext';
import Navigation    from './components/Navigation';
import OfflineBanner from './components/OfflineBanner';
import ErrorBoundary from './components/ErrorBoundary';
import LoginPage     from './pages/LoginPage';
import TimelinePage  from './pages/TimelinePage';
import AlbumsPage       from './pages/AlbumsPage';
import AlbumDetailPage  from './pages/AlbumDetailPage';
import SearchPage    from './pages/SearchPage';
import AdminPage     from './pages/AdminPage';
import DevicesPage   from './pages/DevicesPage';
import SettingsPage  from './pages/SettingsPage';
import NotFoundPage  from './pages/NotFoundPage';
import { loadStoredToken } from './services/api';

function AuthGuard({ children }: { children: React.ReactNode }) {
  const navigate = useNavigate();
  const location = useLocation();
  const token    = loadStoredToken();

  useEffect(() => {
    if (!token && location.pathname !== '/login') navigate('/login');
  }, [token, location.pathname, navigate]);

  if (!token) return null;
  return <>{children}</>;
}

// Wraps page content with the fade-slide animation on route change
function AnimatedPage({ children }: { children: React.ReactNode }) {
  const { pathname } = useLocation();
  return (
    <div key={pathname} className="page-enter" style={{ height: '100%' }}>
      {children}
    </div>
  );
}

function AppLayout() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: '100vh', background: 'var(--bg)' }}>
      <OfflineBanner />
      <div style={{ display: 'flex', flex: 1 }}>
        <Navigation />
        <main style={{ flex: 1, overflowY: 'auto' }}>
          <ErrorBoundary>
            <AnimatedPage>
              <Routes>
                <Route path="/"       element={<TimelinePage />} />
                <Route path="/albums"     element={<AlbumsPage />} />
                <Route path="/albums/:id" element={<AlbumDetailPage />} />
                <Route path="/search" element={<SearchPage />} />
                <Route path="/devices"  element={<DevicesPage />} />
                <Route path="/settings" element={<SettingsPage />} />
                <Route path="/admin"   element={<AdminPage />} />
                <Route path="*"       element={<NotFoundPage />} />
              </Routes>
            </AnimatedPage>
          </ErrorBoundary>
        </main>
      </div>
    </div>
  );
}

export default function App() {
  return (
    <ThemeProvider>
      <ToastProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/*"     element={<AuthGuard><AppLayout /></AuthGuard>} />
          </Routes>
        </BrowserRouter>
      </ToastProvider>
    </ThemeProvider>
  );
}
