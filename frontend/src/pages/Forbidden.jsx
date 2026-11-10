import { Link } from 'react-router';

export default function Forbidden() {
  return (
    <div className="empty" role="alert">
      <p className="empty__title">Not permitted</p>
      <p>Your role cannot use this page. <Link to="/">Go to your home page</Link></p>
    </div>
  );
}
