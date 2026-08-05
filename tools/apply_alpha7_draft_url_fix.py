from pathlib import Path
import re

source_path = Path('app/src/main/java/com/amaral/driverlab/GitHubIssuePublisher.java')
source = source_path.read_text(encoding='utf-8')

source = source.replace(
    'import android.app.Activity;\nimport android.content.Intent;\n',
    'import android.app.Activity;\nimport android.content.ClipData;\nimport android.content.ClipboardManager;\nimport android.content.Intent;\n'
)
source = source.replace(
    'import android.os.Build;\n',
    'import android.os.Build;\nimport android.widget.Toast;\n'
)
source = source.replace(
    'import java.net.HttpURLConnection;\nimport java.net.URL;\n',
    'import java.net.HttpURLConnection;\nimport java.net.URL;\nimport java.net.URLEncoder;\n'
)
source = source.replace(
    '    private static final Pattern REPOSITORY_PART = Pattern.compile("[A-Za-z0-9_.-]{1,100}");\n',
    '    private static final Pattern REPOSITORY_PART = Pattern.compile("[A-Za-z0-9_.-]{1,100}");\n'
    '    private static final int MAX_BROWSER_DRAFT_URL_LENGTH = 3_500;\n'
)

open_draft_pattern = re.compile(
    r'    static void openDraft\(Activity activity, String owner, String repository,\n'
    r'                          JSONObject report\) throws Exception \{.*?\n'
    r'    \}\n\n\n    static String publishQualification',
    re.S
)
open_draft_replacement = '''    static void openDraft(Activity activity, String owner, String repository,
                          JSONObject report) throws Exception {
        validateRepository(owner, repository);
        openIssueDraft(activity, owner, repository,
                issueTitle(report), issueBody(report, false));
    }


    static String publishQualification'''
source, count = open_draft_pattern.subn(open_draft_replacement, source, count=1)
if count != 1:
    raise SystemExit('Could not replace openDraft')

qualification_pattern = re.compile(
    r'    static void openQualificationDraft\(Activity activity, String owner, String repository,\n'
    r'                                       JSONObject manifest\) throws Exception \{.*?\n'
    r'    \}\n\n    static String qualificationIssueTitle',
    re.S
)
qualification_replacement = '''    static void openQualificationDraft(Activity activity, String owner, String repository,
                                       JSONObject manifest) throws Exception {
        validateRepository(owner, repository);
        openIssueDraft(activity, owner, repository,
                qualificationIssueTitle(manifest), qualificationIssueBody(manifest, false));
    }

    private static void openIssueDraft(Activity activity, String owner, String repository,
                                       String title, String body) {
        boolean clipboardFallback = requiresClipboardFallback(owner, repository, title, body);
        Uri uri;
        if (clipboardFallback) {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(
                    Activity.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                throw new IllegalStateException("Área de transferência indisponível");
            }
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    "Amaral Driver Lab — GitHub issue", body));
            uri = buildDraftUri(owner, repository, title, null);
            Toast.makeText(activity, R.string.github_draft_body_copied,
                    Toast.LENGTH_LONG).show();
        } else {
            uri = buildDraftUri(owner, repository, title, body);
        }
        activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
    }

    static boolean requiresClipboardFallback(String owner, String repository,
                                              String title, String body) {
        return estimatedDraftUrlLength(owner, repository, title, body)
                > MAX_BROWSER_DRAFT_URL_LENGTH;
    }

    static int estimatedDraftUrlLength(String owner, String repository,
                                       String title, String body) {
        String base = "https://github.com/" + owner + "/" + repository + "/issues/new";
        int length = base.length() + "?title=".length() + encodedLength(title);
        if (body != null) {
            length += "&body=".length() + encodedLength(body);
        }
        return length;
    }

    private static int encodedLength(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value,
                    StandardCharsets.UTF_8.name()).length();
        } catch (Exception impossible) {
            throw new IllegalStateException("UTF-8 indisponível", impossible);
        }
    }

    private static Uri buildDraftUri(String owner, String repository,
                                     String title, String body) {
        Uri.Builder builder = Uri.parse("https://github.com/" + owner + "/" + repository
                        + "/issues/new")
                .buildUpon()
                .appendQueryParameter("title", title);
        if (body != null) builder.appendQueryParameter("body", body);
        return builder.build();
    }

    static String qualificationIssueTitle'''
source, count = qualification_pattern.subn(qualification_replacement, source, count=1)
if count != 1:
    raise SystemExit('Could not replace openQualificationDraft')

source_path.write_text(source, encoding='utf-8')

build_path = Path('app/build.gradle')
build = build_path.read_text(encoding='utf-8')
build = build.replace("versionCode 19\n        versionName '0.13.0-alpha6'",
                      "versionCode 20\n        versionName '0.13.0-alpha7'")
if "versionName '0.13.0-alpha7'" not in build:
    raise SystemExit('Could not bump alpha7 version')
build_path.write_text(build, encoding='utf-8')

def add_string(path, line):
    xml_path = Path(path)
    xml = xml_path.read_text(encoding='utf-8')
    if 'name="github_draft_body_copied"' not in xml:
        xml = xml.replace('</resources>', f'    {line}\n</resources>')
    xml_path.write_text(xml, encoding='utf-8')

add_string(
    'app/src/main/res/values/strings.xml',
    '<string name="github_draft_body_copied">The full report was copied. Paste it into the GitHub issue description.</string>'
)
add_string(
    'app/src/main/res/values-pt-rBR/strings.xml',
    '<string name="github_draft_body_copied">O relatório completo foi copiado. Cole-o na descrição da issue do GitHub.</string>'
)

test_path = Path('app/src/test/java/com/amaral/driverlab/GitHubIssuePublisherDraftTest.java')
test_path.write_text('''package com.amaral.driverlab;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GitHubIssuePublisherDraftTest {
    @Test
    public void shortDraftRemainsInsideBrowserUrl() {
        assertFalse(GitHubIssuePublisher.requiresClipboardFallback(
                "rickamaral94",
                "Amaral-Driver-Lab",
                "[Turnip Validation] short",
                "## Result\\n\\nSmall report."));
    }

    @Test
    public void richQualificationReportUsesClipboardFallback() {
        String stage = "### Stage\\n\\n| Metric | Reference | Candidate | Delta |\\n"
                + "|---|---:|---:|---:|\\n| P99 | 10.25 ms | 9.75 ms | +4.88% |\\n\\n";
        String body = stage.repeat(120);

        assertTrue(GitHubIssuePublisher.requiresClipboardFallback(
                "rickamaral94",
                "Amaral-Driver-Lab",
                "[Turnip Validation] CANDIDATO alpha7 vs REFERÊNCIA alpha6",
                body));
    }

    @Test
    public void encodedUnicodeAndMarkdownAreCounted() {
        String body = "Comparação CANDIDATO × REFERÊNCIA — métricas, confiança e validação. ".repeat(80);

        assertTrue(GitHubIssuePublisher.estimatedDraftUrlLength(
                "rickamaral94", "Amaral-Driver-Lab", "Validação", body) > body.length());
    }
}
''', encoding='utf-8')
