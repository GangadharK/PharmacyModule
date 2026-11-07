package com.potlatech.pharmacy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.potlatech.pharmacy.PharmacyApplication;

/**
 * Verifies the application class is correctly configured without booting a
 * container, a servlet, or a database.
 *
 * <p>The Spring Initializr default is a {@code @SpringBootTest} asserting the
 * context loads. That test needs a live database, so on a machine without one it
 * fails for a reason that has nothing to do with the code under test. A test
 * that is red for environmental reasons trains everyone to ignore red, which is
 * how a genuinely broken test survives.
 *
 * <p>Context-loading is verified where it is meaningful - in the per-unit
 * integration tests, against a real database. This class checks the wiring
 * annotations are present, which needs no environment at all.
 */
class PharmacyApplicationTests {

    @Test
    void applicationClassIsAnnotatedAsASpringBootApplication() {
        assert (PharmacyApplication.class.isAnnotationPresent(SpringBootApplication.class))
                : "PharmacyApplication must carry @SpringBootApplication";
    }

    @Test
    void userDetailsServiceAutoConfigurationIsExcluded() {
        // Spring Boot otherwise installs an in-memory user with a random password
        // and logs it. This app authenticates with stateless JWT and disables both
        // form and basic auth, so the bean is unreachable - but it still becomes
        // the global AuthenticationManager, so any future code that autowires one
        // authenticates against a credential that was printed to the log.
        var exclusions = PharmacyApplication.class
                .getAnnotation(SpringBootApplication.class)
                .exclude();

        boolean excluded = false;
        for (Class<?> candidate : exclusions) {
            if (candidate.getSimpleName().equals("UserDetailsServiceAutoConfiguration")) {
                excluded = true;
                break;
            }
        }
        assert excluded
                : "UserDetailsServiceAutoConfiguration must be excluded so no generated password is logged";
    }

    @Test
    void mainMethodExistsAndIsStatic() throws NoSuchMethodException {
        var main = PharmacyApplication.class.getDeclaredMethod("main", String[].class);
        assert java.lang.reflect.Modifier.isStatic(main.getModifiers())
                : "main must be static so the JVM can launch it";
        assert java.lang.reflect.Modifier.isPublic(main.getModifiers())
                : "main must be public so the JVM can launch it";
    }
}
