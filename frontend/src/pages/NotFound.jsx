import { Link } from 'react-router';

export default function NotFound() {
  return (
    <div className="empty">
      <p className="empty__title">Page not found</p>
      <p><Link to="/">Go to your home page</Link></p>
    </div>
  );
}
