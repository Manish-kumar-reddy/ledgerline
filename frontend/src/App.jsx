import { Navigate, Route, Routes } from 'react-router';
import { AuthProvider } from './auth/AuthContext.jsx';
import { homePathFor, useAuth } from './auth/session.js';
import { RequireAuth, RequireRole } from './auth/guards.jsx';
import AppShell from './components/AppShell.jsx';
import AccountPage from './pages/AccountPage.jsx';
import AccountsPage from './pages/AccountsPage.jsx';
import Login from './pages/Login.jsx';
import NotFound from './pages/NotFound.jsx';
import OversightAccountsPage from './pages/OversightAccountsPage.jsx';
import OversightStatementPage from './pages/OversightStatementPage.jsx';
import ReconciliationPage from './pages/ReconciliationPage.jsx';
import Register from './pages/Register.jsx';

const HOLDER = ['ACCOUNT_HOLDER'];
const STAFF = ['ADMIN', 'AUDITOR'];

function Home() {
  const { user } = useAuth();
  return <Navigate to={homePathFor(user)} replace />;
}

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/register" element={<Register />} />
      <Route element={<RequireAuth><AppShell /></RequireAuth>}>
        <Route index element={<Home />} />
        <Route path="accounts" element={<RequireRole roles={HOLDER}><AccountsPage /></RequireRole>} />
        <Route path="accounts/:id" element={<RequireRole roles={HOLDER}><AccountPage /></RequireRole>} />
        <Route path="oversight/accounts" element={<RequireRole roles={STAFF}><OversightAccountsPage /></RequireRole>} />
        <Route path="oversight/accounts/:id" element={<RequireRole roles={STAFF}><OversightStatementPage /></RequireRole>} />
        <Route path="oversight/reconciliation" element={<RequireRole roles={STAFF}><ReconciliationPage /></RequireRole>} />
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  );
}

export default function App() {
  return (
    <AuthProvider>
      <AppRoutes />
    </AuthProvider>
  );
}
