package com.hikmethankolay.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Keeps {@code deploy/kubernetes} deploying the image this repository releases: the image the Deployment runs must
 * be the one Kustomize pins, by tag and digest, the migration Job must run the same image, and the pinned tag must be
 * the latest release in {@code CHANGELOG.md}, so a release cannot ship while the manifests still deploy an older one.
 * It also guards the hardening that is easy to lose in an edit: egress rules and secrets mounted as files.
 */
class KubernetesManifestTest {

    private static final Path KUSTOMIZATION = Path.of("deploy", "kubernetes", "kustomization.yaml");
    private static final Path DEPLOYMENT = Path.of("deploy", "kubernetes", "deployment.yaml");
    private static final Path CHANGELOG = Path.of("CHANGELOG.md");
    private static final Path MIGRATE_JOB = Path.of("deploy", "kubernetes", "migrate-job.yaml");
    private static final Path NETWORK_POLICY = Path.of("deploy", "kubernetes", "networkpolicy.yaml");

    @Test
    void kustomizePinsTheImageTheDeploymentRuns() throws IOException {
        String deployed = find(DEPLOYMENT, "image: (\\S+)");

        assertThat(find(KUSTOMIZATION, "- name: (\\S+)")).isEqualTo(deployed);
    }

    @Test
    void theImageIsPinnedByDigestAndTheMigrationJobRunsIt() throws IOException {
        String digest = find(KUSTOMIZATION, "digest: (sha256:[0-9a-f]{64})");
        String pinned = find(KUSTOMIZATION, "- name: (\\S+)") + ":" + find(KUSTOMIZATION, "newTag: \"?([^\"\\s]+)")
                + "@" + digest;

        assertThat(find(MIGRATE_JOB, "image: (\\S+)"))
                .as("image in %s", MIGRATE_JOB)
                .isEqualTo(pinned);
    }

    @Test
    void theNetworkPolicyLimitsIngressAndEgress() throws IOException {
        String policy = Files.readString(NETWORK_POLICY);

        assertThat(policy).contains("policyTypes: [\"Ingress\", \"Egress\"]").contains("port: 53");
    }

    @Test
    void secretsAreMountedAsFilesNotEnvironmentVariables() throws IOException {
        for (Path manifest : new Path[] {DEPLOYMENT, MIGRATE_JOB}) {
            String text = Files.readString(manifest);

            assertThat(text).as("%s", manifest).doesNotContain("secretRef").contains("mountPath: /etc/app/secrets");
        }
    }

    @Test
    void pinnedTagIsTheLatestRelease() throws IOException {
        Matcher release = Pattern.compile("^## \\[(\\d+\\.\\d+\\.\\d+)]", Pattern.MULTILINE)
                .matcher(Files.readString(CHANGELOG));
        // A project made from the template starts with an empty changelog: nothing is released yet.
        assumeTrue(release.find(), "no release in CHANGELOG.md");

        assertThat(find(KUSTOMIZATION, "newTag: \"?([^\"\\s]+)"))
                .as("newTag in %s; bump it with every release", KUSTOMIZATION)
                .isEqualTo(release.group(1));
    }

    private static String find(Path file, String regex) throws IOException {
        Matcher matcher = Pattern.compile(regex).matcher(Files.readString(file));
        assertThat(matcher.find()).as("%s in %s", regex, file).isTrue();
        return matcher.group(1);
    }
}
