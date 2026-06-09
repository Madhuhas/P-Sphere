import { NavLink, useNavigate } from 'react-router-dom';
import { useTheme } from '../context/ThemeContext';

const links = [
  { to: '/',        label: 'Timeline', icon: '🖼' },
  { to: '/albums',  label: 'Albums',   icon: '📁' },
  { to: '/search',  label: 'Search',   icon: '🔍' },
  { to: '/devices',  label: 'Devices',  icon: '📱' },
  { to: '/settings', label: 'Settings', icon: '🔧' },
  { to: '/admin',    label: 'Admin',    icon: '⚙' },
];

export default function Navigation() {
  const navigate    = useNavigate();
  const { theme, toggle } = useTheme();

  function logout() {
    localStorage.removeItem('psphere_jwt');
    navigate('/login');
  }

  return (
    <nav className="nav-sidebar" style={nav}>
      <div style={logo}><span className="nav-label">P‑Sphere</span></div>

      {links.map(({ to, label, icon }) => (
        <NavLink key={to} to={to} end={to === '/'}
          style={({ isActive }) => ({ ...link, ...(isActive ? activeStyle : {}) })}>
          <span style={{ fontSize: 18 }}>{icon}</span>
          <span className="nav-label" style={{ marginLeft: 10 }}>{label}</span>
        </NavLink>
      ))}

      <div style={{ marginTop: 'auto', display: 'flex', flexDirection: 'column', gap: 8 }}>
        <button style={iconBtn} onClick={toggle} title="Toggle theme">
          {theme === 'dark' ? '☀' : '🌙'}
          <span className="nav-label" style={{ marginLeft: 10, fontSize: 13 }}>
            {theme === 'dark' ? 'Light mode' : 'Dark mode'}
          </span>
        </button>
        <button style={iconBtn} onClick={logout} title="Sign out">
          ↩
          <span className="nav-label" style={{ marginLeft: 10, fontSize: 13 }}>Sign out</span>
        </button>
      </div>
    </nav>
  );
}

const nav: React.CSSProperties = {
  display: 'flex', flexDirection: 'column',
  width: 190, minHeight: '100vh',
  background: 'var(--nav-bg)',
  padding: '24px 12px', gap: 4,
  transition: 'width .2s',
  flexShrink: 0,
};
const logo: React.CSSProperties  = { color: 'var(--accent)', fontWeight: 700, fontSize: 18, marginBottom: 20, paddingLeft: 12, overflow: 'hidden', whiteSpace: 'nowrap' };
const link: React.CSSProperties  = { display: 'flex', alignItems: 'center', color: 'var(--text-muted)', textDecoration: 'none', padding: '10px 12px', borderRadius: 8, fontSize: 14, transition: 'background .15s, color .15s', overflow: 'hidden', whiteSpace: 'nowrap' };
const activeStyle: React.CSSProperties = { background: 'var(--surface2)', color: 'var(--accent)', fontWeight: 600 };
const iconBtn: React.CSSProperties = { display: 'flex', alignItems: 'center', background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer', padding: '8px 12px', borderRadius: 8, width: '100%', textAlign: 'left', fontSize: 16, overflow: 'hidden', whiteSpace: 'nowrap' };
