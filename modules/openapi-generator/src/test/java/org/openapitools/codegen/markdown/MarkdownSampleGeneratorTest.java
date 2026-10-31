package org.openapitools.codegen.markdown;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import org.openapitools.codegen.ClientOptInput;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenParameter;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.TestUtils;
import org.openapitools.codegen.config.CodegenConfigurator;
import org.openapitools.codegen.languages.MarkdownDocumentationCodegen;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

public class MarkdownSampleGeneratorTest {

    @Test(description = "test special characters in MIME type")
    public void testSpecialCharactersInMimeType() throws Exception {
        // for https://github.com/OpenAPITools/openapi-generator/issues/15923
        // without the fix, the following will throw exception "IllegalArgument character to be escaped is missing"
        final OpenAPI openAPI = TestUtils.parseFlattenSpec("src/test/resources/3_0/issue_15923.yaml");
        final MarkdownDocumentationCodegen codegen = new MarkdownDocumentationCodegen();
        codegen.setOpenAPI(openAPI);

        final String requestPath = "/v1/MyRequest";
        Operation textOperation = openAPI.getPaths().get(requestPath).getPut();
        CodegenOperation operation = codegen.fromOperation(requestPath, "put", textOperation, null);
        CodegenParameter codegenParameter = operation.allParams.get(0);
        Assert.assertNotNull(codegenParameter);
    }

    @Test(description = "README shows the OpenAPI 3.2 tag hierarchy and API docs show a parent breadcrumb")
    public void testTagHierarchyInReadmeAndApiDoc() throws Exception {
        Path output = Files.createTempDirectory("markdown-tag-hierarchy");
        try {
            ClientOptInput input = new CodegenConfigurator()
                    .setGeneratorName("markdown")
                    .setInputSpec("src/test/resources/3_2/tag-hierarchy.yaml")
                    .setOutputDir(output.toAbsolutePath().toString())
                    .toClientOptInput();
            new DefaultGenerator().opts(input).generate();

            String readme = Files.readString(output.resolve("README.md"));
            Assert.assertTrue(readme.contains("<a name=\"documentation-for-api-groups\"></a>\n## API Groups"),
                    "README is missing the API Groups section");
            Assert.assertTrue(readme.contains("- [PetsApi](Apis/PetsApi.md) — Pets Summary *(kind: nav)*"),
                    "README is missing the root PetsApi line with summary and explicit kind");
            Assert.assertTrue(readme.contains("  - [DogsApi](Apis/DogsApi.md)\n    - [PuppiesApi](Apis/PuppiesApi.md)"),
                    "README is missing the nested DogsApi/PuppiesApi lines");
            Assert.assertTrue(readme.contains("- animals (no operations)\n  - [CatsApi](Apis/CatsApi.md)"),
                    "README is missing the parent tag without operations");
            // webhook groups are part of the union and link to their generated api document
            Assert.assertTrue(readme.contains("  - [WebhookPetApi](Apis/WebhookPetApi.md) — Webhook Pet Summary *(kind: nav)*"),
                    "README is missing the webhook-only group as a linked node");

            String puppiesApi = Files.readString(output.resolve("Apis").resolve("PuppiesApi.md"));
            Assert.assertTrue(puppiesApi.contains("Parent: [PetsApi](PetsApi.md) › [DogsApi](DogsApi.md)"),
                    "PuppiesApi.md is missing the parent breadcrumb");
            String catsApi = Files.readString(output.resolve("Apis").resolve("CatsApi.md"));
            Assert.assertTrue(catsApi.contains("Parent: animals"),
                    "CatsApi.md is missing the unlinked parent name");
        } finally {
            deleteRecursively(output);
        }
    }

    @Test(description = "group-level cycles created by sanitizeTag collisions are cut everywhere")
    public void testSanitizedCollisionCycleIsConsistent() throws Exception {
        Path output = Files.createTempDirectory("markdown-tag-collision");
        try {
            ClientOptInput input = new CodegenConfigurator()
                    .setGeneratorName("markdown")
                    .setInputSpec("src/test/resources/3_2/tag-hierarchy.yaml")
                    .setOutputDir(output.toAbsolutePath().toString())
                    .toClientOptInput();
            new DefaultGenerator().opts(input).generate();

            String readme = Files.readString(output.resolve("README.md"));
            // 'Alpha' -> 'Beta' -> 'alpha' is not a cycle in raw names, but 'Alpha' and
            // 'alpha' sanitize to the same group 'Alpha', closing a group-level cycle:
            // both edges are cut so every group still appears exactly once, as a root
            String groups = readme.substring(readme.indexOf("## API Groups"),
                    readme.indexOf("documentation-for-models"));
            Assert.assertEquals(countOccurrences(groups, "AlphaApi.md"), 1,
                    "AlphaApi must appear exactly once in the hierarchy");
            Assert.assertEquals(countOccurrences(groups, "BetaApi.md"), 1,
                    "BetaApi must appear exactly once in the hierarchy");
            Assert.assertTrue(groups.contains("- [AlphaApi](Apis/AlphaApi.md)\n- [BetaApi](Apis/BetaApi.md)"),
                    "Alpha and Beta must both be roots in the hierarchy");
            // the same-name raw cycle behaves the same: A and B are roots, X nests under A
            Assert.assertTrue(groups.contains("- [AApi](Apis/AApi.md)\n  - [XApi](Apis/XApi.md)\n- [BApi](Apis/BApi.md)"),
                    "A and B must be roots with X nested under A");

            // breadcrumbs agree with the tree: no Parent line on cycle members' docs
            String alphaApi = Files.readString(output.resolve("Apis").resolve("AlphaApi.md"));
            Assert.assertFalse(alphaApi.contains("Parent:"),
                    "AlphaApi.md must not show a Parent for a cut cycle edge");
            String betaApi = Files.readString(output.resolve("Apis").resolve("BetaApi.md"));
            Assert.assertFalse(betaApi.contains("Parent:"),
                    "BetaApi.md must not show a Parent for a cut cycle edge");
            String aApi = Files.readString(output.resolve("Apis").resolve("AApi.md"));
            Assert.assertFalse(aApi.contains("Parent:"),
                    "AApi.md must not show a Parent for a cut cycle edge");
            String bApi = Files.readString(output.resolve("Apis").resolve("BApi.md"));
            Assert.assertFalse(bApi.contains("Parent:"),
                    "BApi.md must not show a Parent for a cut cycle edge");
            String xApi = Files.readString(output.resolve("Apis").resolve("XApi.md"));
            Assert.assertTrue(xApi.contains("Parent: [AApi](AApi.md)"),
                    "XApi.md breadcrumb must match the README nesting of X under A");
        } finally {
            deleteRecursively(output);
        }
    }

    @Test(description = "colliding tag names resolve through the operations' tag, shared by tree and breadcrumb")
    public void testCollidingTagNamesResolveConsistently() throws Exception {
        Path output = Files.createTempDirectory("markdown-tag-colliding-names");
        try {
            ClientOptInput input = new CodegenConfigurator()
                    .setGeneratorName("markdown")
                    .setInputSpec("src/test/resources/3_2/tag-hierarchy.yaml")
                    .setOutputDir(output.toAbsolutePath().toString())
                    .toClientOptInput();
            new DefaultGenerator().opts(input).generate();

            String readme = Files.readString(output.resolve("README.md"));
            // 'foo-bar' (parent 'one') and 'foo_bar' (parent 'two') sanitize to one group
            // 'FooBar'; the operation names 'foo_bar', so both views pick parent 'two'
            Assert.assertTrue(readme.contains("- two (no operations)\n  - [FooBarApi](Apis/FooBarApi.md)"),
                    "README must nest FooBarApi under 'two'");
            String fooBarApi = Files.readString(output.resolve("Apis").resolve("FooBarApi.md"));
            Assert.assertTrue(fooBarApi.contains("Parent: two"),
                    "FooBarApi.md breadcrumb must show 'two', matching the README parent");
        } finally {
            deleteRecursively(output);
        }
    }

    @Test(description = "markdown metacharacters in tag names and summaries are escaped")
    public void testMarkdownMetacharactersAreEscaped() throws Exception {
        Path output = Files.createTempDirectory("markdown-tag-escaping");
        try {
            ClientOptInput input = new CodegenConfigurator()
                    .setGeneratorName("markdown")
                    .setInputSpec("src/test/resources/3_2/tag-hierarchy.yaml")
                    .setOutputDir(output.toAbsolutePath().toString())
                    .toClientOptInput();
            new DefaultGenerator().opts(input).generate();

            String readme = Files.readString(output.resolve("README.md"));
            // '<team>' must not become an inline HTML tag, '[x](url)' must not become a
            // link, and '|'/'*' must not break a table or emphasize — backslash escapes
            Assert.assertTrue(readme.contains("- \\<team\\> \\[x\\](url) (no operations) — a\\|b \\*c\\*"),
                    "README must render the special-character tag name and summary escaped");
            Assert.assertTrue(readme.contains("  - [EscChildApi](Apis/EscChildApi.md)"),
                    "README must nest EscChildApi under the escaped parent node");
            String escChildApi = Files.readString(output.resolve("Apis").resolve("EscChildApi.md"));
            Assert.assertTrue(escChildApi.contains("Parent: \\<team\\> \\[x\\](url)"),
                    "EscChildApi.md breadcrumb must escape the parent tag name");
        } finally {
            deleteRecursively(output);
        }
    }

    @Test(description = "an api+webhook group sharing one name is emitted once and linked in the hierarchy")
    public void testSameNameApiWebhookGroup() throws Exception {
        Path output = Files.createTempDirectory("markdown-tag-same-name");
        try {
            ClientOptInput input = new CodegenConfigurator()
                    .setGeneratorName("markdown")
                    .setInputSpec("src/test/resources/3_2/tag-hierarchy-same-name.yaml")
                    .setOutputDir(output.toAbsolutePath().toString())
                    .toClientOptInput();
            new DefaultGenerator().opts(input).generate();

            String readme = Files.readString(output.resolve("README.md"));
            // 'root-tag' has no operations anywhere; 'shared' groups one api operation and
            // one webhook operation under a single generated api document
            Assert.assertTrue(readme.contains("- root-tag (no operations) — Root Tag Summary *(kind: nav)*\n"
                            + "  - [SharedApi](Apis/SharedApi.md)"),
                    "README must nest the shared api+webhook group under root-tag");
            // the union counts webhook operations, so 'shared' has operations even though
            // the api document was overwritten by the webhook document of the same name
            Assert.assertTrue(Files.exists(output.resolve("Apis").resolve("SharedApi.md")),
                    "SharedApi.md must be generated");
        } finally {
            deleteRecursively(output);
        }
    }

    @Test(description = "hierarchy links follow apiPackage/toApiFilename, not the apiDocPath property")
    public void testHierarchyLinksIgnoreApiDocPath() throws Exception {
        Path output = Files.createTempDirectory("markdown-tag-apidocpath");
        try {
            ClientOptInput input = new CodegenConfigurator()
                    .setGeneratorName("markdown")
                    .setInputSpec("src/test/resources/3_2/tag-hierarchy.yaml")
                    .setOutputDir(output.toAbsolutePath().toString())
                    .addAdditionalProperty("apiDocPath", "docs/")
                    .toClientOptInput();
            new DefaultGenerator().opts(input).generate();

            String readme = Files.readString(output.resolve("README.md"));
            String groups = readme.substring(readme.indexOf("## API Groups"),
                    readme.indexOf("documentation-for-models"));
            // the api documents still live in Apis/, so the hierarchy must link there —
            // the apiDocPath property never moved the emitted files
            Assert.assertFalse(groups.contains("docs/"),
                    "hierarchy links must not follow apiDocPath");
            Assert.assertTrue(groups.contains("[PetsApi](Apis/PetsApi.md)"),
                    "hierarchy link must point at the emitted api document");
        } finally {
            deleteRecursively(output);
        }
    }

    @Test(description = "documents without tag parents do not get an API Groups section")
    public void testNoApiGroupsSectionWithoutTagParents() throws Exception {
        Path output = Files.createTempDirectory("markdown-no-tag-hierarchy");
        try {
            ClientOptInput input = new CodegenConfigurator()
                    .setGeneratorName("markdown")
                    .setInputSpec("src/test/resources/3_2/query-operation.yaml")
                    .setOutputDir(output.toAbsolutePath().toString())
                    .toClientOptInput();
            new DefaultGenerator().opts(input).generate();

            String readme = Files.readString(output.resolve("README.md"));
            Assert.assertFalse(readme.contains("API Groups"),
                    "README unexpectedly contains an API Groups section");
        } finally {
            deleteRecursively(output);
        }
    }

    @Test(description = "specs without tag parents keep byte-for-byte output")
    public void testOutputUnchangedWithoutTagParents() throws Exception {
        assertCompatOutput("src/test/resources/3_0/tag-compat.yaml", EXPECTED_COMPAT_README, EXPECTED_COMPAT_API_DOC);
        assertCompatOutput("src/test/resources/3_1/tag-compat.yaml", EXPECTED_COMPAT_README, EXPECTED_COMPAT_API_DOC);
        assertCompatOutput("src/test/resources/3_2/tag-compat.yaml", EXPECTED_COMPAT_README, EXPECTED_COMPAT_API_DOC);
    }

    private void assertCompatOutput(String spec, String expectedReadme, String expectedApiDoc) throws Exception {
        Path output = Files.createTempDirectory("markdown-compat");
        try {
            ClientOptInput input = new CodegenConfigurator()
                    .setGeneratorName("markdown")
                    .setInputSpec(spec)
                    .setOutputDir(output.toAbsolutePath().toString())
                    .toClientOptInput();
            new DefaultGenerator().opts(input).generate();

            Assert.assertEquals(Files.readString(output.resolve("README.md")), expectedReadme,
                    spec + ": README.md changed");
            Assert.assertEquals(Files.readString(output.resolve("Apis").resolve("PetsApi.md")), expectedApiDoc,
                    spec + ": PetsApi.md changed");
        } finally {
            deleteRecursively(output);
        }
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static final String EXPECTED_COMPAT_README =
        "# Documentation for compat doc\n" +
        "\n" +
        "<a name=\"documentation-for-api-endpoints\"></a>\n" +
        "## Documentation for API Endpoints\n" +
        "\n" +
        "All URIs are relative to *http://localhost*\n" +
        "\n" +
        "| Class | Method | HTTP request | Description |\n" +
        "|------------ | ------------- | ------------- | -------------|\n" +
        "| *PetsApi* | [**listPets**](Apis/PetsApi.md#listPets) | **GET** /pets |  |\n" +
        "\n" +
        "\n" +
        "<a name=\"documentation-for-models\"></a>\n" +
        "## Documentation for Models\n" +
        "\n" +
        "\n" +
        "\n" +
        "<a name=\"documentation-for-authorization\"></a>\n" +
        "## Documentation for Authorization\n" +
        "\n" +
        "All endpoints do not require authorization.\n";

    private static final String EXPECTED_COMPAT_API_DOC =
        "# PetsApi\n" +
        "\n" +
        "All URIs are relative to *http://localhost*\n" +
        "\n" +
        "| Method | HTTP request | Description |\n" +
        "|------------- | ------------- | -------------|\n" +
        "| [**listPets**](PetsApi.md#listPets) | **GET** /pets |  |\n" +
        "\n" +
        "\n" +
        "<a name=\"listPets\"></a>\n" +
        "# **listPets**\n" +
        "> listPets()\n" +
        "\n" +
        "\n" +
        "\n" +
        "### Parameters\n" +
        "This endpoint does not need any parameter.\n" +
        "\n" +
        "### Return type\n" +
        "\n" +
        "null (empty response body)\n" +
        "\n" +
        "### Authorization\n" +
        "\n" +
        "No authorization required\n" +
        "\n" +
        "### HTTP request headers\n" +
        "\n" +
        "- **Content-Type**: Not defined\n" +
        "- **Accept**: Not defined\n" +
        "\n" +
        "";
}
