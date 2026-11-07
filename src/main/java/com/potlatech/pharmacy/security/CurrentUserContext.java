package com.potlatech.pharmacy.security;

import com.potlatech.pharmacy.common.exception.AccessForbiddenException;

/**
 * The logged-in user, for service-layer code that needs to stamp
 * {@code performed_by} / {@code created_by}.
 *
 * <p>Thin by design. It reads the principal Spring Security already put in the
 * {@code SecurityContext} rather than holding a second copy, so there is nothing
 * to keep in sync and no way for the two to disagree.
 *
 * <p>Rejected rather than defaulted when absent. A null actor would let a stock
 * transaction be written with no one accountable for it, which is precisely the
 * audit gap the {@code performed_by} column exists to close — an anonymous
 * system action is legitimate (an expiry sweep) but must be written
 * <em>deliberately</em> as null, not as a silent consequence of forgetting to
 * look the user up.
 *
 * <p>Slim on purpose. {@code ../SCMS} carries a full tenant context through every
 * layer; this service has no tenancy, and the only identity question any domain
 * code asks is "who did this".
 */
public interface CurrentUserContext {

    /**
     * @return the authenticated user.
     * @throws AccessForbiddenException if there is no authenticated caller, which
     *                                  means the endpoint was reached without the
     *                                  security filter having run — a
     *                                  misconfiguration, not a user error.
     */
    AuthenticatedUser require();

    /** @return the authenticated user, or {@code null} if there is none. */
    AuthenticatedUser find();

    /** @return the caller's id, or {@code null} for a system-initiated action. */
    java.util.UUID idOrNull();
}
