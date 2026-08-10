package io.relay.app.e2e

/**
 * Marks a test that only makes sense when the host side of the E2E is driving
 * it — it holds a live sharing session open and waits for a marker file the
 * workflow pushes. Excluded from the ordinary `connectedAndroidTest` run
 * (`notAnnotation`) and invoked explicitly by `.github/workflows/e2e.yml`.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class HostHarness
