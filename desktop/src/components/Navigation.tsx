import { NavLink } from 'react-router-dom';

const links = [
  { to: '/',       label: 'Dashboard' },
  { to: '/sync',   label: 'Sync'      },
  { to: '/admin',  label: 'Admin'     },
];

export default function Navigation() {
  return (
    <nav style={styles.nav}>
      <div style={styles.logo}>P-Sphere</div>
      {links.map(({ to, label }) => (
        <NavLink
          key={to}
          to={to}
          end={to === '/'}
          style={({ isActive }) => ({ ...styles.link, ...(isActive ? styles.active : {}) })}
        >
          {label}
        </NavLink>
      ))}
    </nav>
  );
}

const styles: Record<string, React.CSSProperties> = {
  nav:    { display:'flex', flexDirection:'column', width:200, minHeight:'100vh', background:'#1a1a2e', padding:'24px 16px', gap:8 },
  logo:   { color:'#90caf9', fontWeight:700, fontSize:20, marginBottom:24, paddingLeft:8 },
  link:   { color:'#aaa', textDecoration:'none', padding:'10px 12px', borderRadius:8, fontSize:14 },
  active: { background:'#162447', color:'#90caf9', fontWeight:600 },
};
