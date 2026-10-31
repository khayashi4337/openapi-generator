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

import java.nio.file.Files;
import java.nio.file.Path;

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
        ClientOptInput input = new CodegenConfigurator()
                .setGeneratorName("markdown")
                .setInputSpec("src/test/resources/3_2/tag-hierarchy.yaml")
                .setOutputDir(output.toAbsolutePath().toString())
                .toClientOptInput();
        new DefaultGenerator().opts(input).generate();

        String readme = Files.readString(output.resolve("README.md"));
        Assert.assertTrue(readme.contains("<a name=\"documentation-for-api-groups\"></a>\n## API Groups"),
                "README is missing the API Groups section");
        Assert.assertTrue(readme.contains("- [PetsApi](Apis/PetsApi.md) — Pets Summary *(nav)*"),
                "README is missing the root PetsApi line with summary and kind");
        Assert.assertTrue(readme.contains("  - [DogsApi](Apis/DogsApi.md)\n    - [PuppiesApi](Apis/PuppiesApi.md)"),
                "README is missing the nested DogsApi/PuppiesApi lines");
        Assert.assertTrue(readme.contains("- animals (no operations)\n  - [CatsApi](Apis/CatsApi.md)"),
                "README is missing the parent tag without operations");

        String puppiesApi = Files.readString(output.resolve("Apis").resolve("PuppiesApi.md"));
        Assert.assertTrue(puppiesApi.contains("Parent: [PetsApi](PetsApi.md) › [DogsApi](DogsApi.md)"),
                "PuppiesApi.md is missing the parent breadcrumb");
        String catsApi = Files.readString(output.resolve("Apis").resolve("CatsApi.md"));
        Assert.assertTrue(catsApi.contains("Parent: animals"),
                "CatsApi.md is missing the unlinked parent name");
    }

    @Test(description = "documents without tag parents do not get an API Groups section")
    public void testNoApiGroupsSectionWithoutTagParents() throws Exception {
        Path output = Files.createTempDirectory("markdown-no-tag-hierarchy");
        ClientOptInput input = new CodegenConfigurator()
                .setGeneratorName("markdown")
                .setInputSpec("src/test/resources/3_2/query-operation.yaml")
                .setOutputDir(output.toAbsolutePath().toString())
                .toClientOptInput();
        new DefaultGenerator().opts(input).generate();

        String readme = Files.readString(output.resolve("README.md"));
        Assert.assertFalse(readme.contains("API Groups"),
                "README unexpectedly contains an API Groups section");
    }

}
