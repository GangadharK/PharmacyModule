package com.potlatech.pharmacy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code app.cookie.*}.
 *
 * @param secure    Secure attribute. Default true; the dev profile relaxes it,
 *                  because a browser drops a Secure cookie sent over plain HTTP
 *                  and that presents as "refresh is silently broken".
 * @param sameSite  Strict, Lax or None.
 * @param path      scoped so the browser never attaches the refresh token to
 *                  catalogue or inventory requests — only {@code /auth/refresh}
 *                  and {@code /auth/logout} receive it. The access token is in
 *                  an {@code Authorization} header, which this scoping does not
 *                  affect.
 */
@ConfigurationProperties(prefix = "app.cookie")
public record CookieProperties(boolean secure, String sameSite, String path) {
}
