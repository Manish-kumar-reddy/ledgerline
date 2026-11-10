const bytes = (text) => new TextEncoder().encode(text).length;

/** Mirrors the backend rules (RegisterRequest) for early feedback; the backend still validates everything. */
export function validateRegistration({ fullName, email, password }) {
  const errors = {};
  if (!fullName.trim()) errors.fullName = 'Enter your full name.';
  else if (fullName.trim().length > 100) errors.fullName = 'At most 100 characters.';
  if (!/^[\x21-\x7E]+@[\x21-\x7E]+$/.test(email.trim()) || email.trim().length > 254) errors.email = 'Enter a valid email address.';
  const size = bytes(password);
  if (size < 12) errors.password = 'Use at least 12 characters.';
  else if (size > 72) errors.password = 'Too long: at most 72 bytes.';
  return errors;
}
