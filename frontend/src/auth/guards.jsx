import { Navigate, useLocation } from 'react-router';
import { useAuth } from './session.js';
import Forbidden from '../pages/Forbidden.jsx';

// UI routing only. These guards decide what to render; they are not a security control. Every API call is
// authorized again by the backend (and a 403 from it is shown as "not permitted").

export function RequireAuth({ children }) {
  const { user } = useAuth();
  const location = useLocation();
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return children;
}

export function RequireRole({ roles, children }) {
  const { user } = useAuth();
  return user && roles.includes(user.role) ? children : <Forbidden />;
}
