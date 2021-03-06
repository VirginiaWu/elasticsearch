/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.messy.tests;

import org.elasticsearch.action.admin.cluster.storedscripts.GetStoredScriptResponse;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.template.SearchTemplateAction;
import org.elasticsearch.action.search.template.SearchTemplateRequest;
import org.elasticsearch.action.search.template.SearchTemplateRequestBuilder;
import org.elasticsearch.action.search.template.SearchTemplateResponse;
import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.TemplateQueryBuilder;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.rest.action.search.template.RestSearchTemplateAction;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.script.ScriptService.ScriptType;
import org.elasticsearch.script.Template;
import org.elasticsearch.script.mustache.MustachePlugin;
import org.elasticsearch.script.mustache.MustacheScriptEngineService;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.test.ESIntegTestCase;
import org.junit.Before;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertNoFailures;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Full integration test of the template query plugin.
 */
@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.SUITE)
public class TemplateQueryTests extends ESIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Collections.singleton(MustachePlugin.class);
    }

    @Override
    protected Collection<Class<? extends Plugin>> transportClientPlugins() {
        return nodePlugins();
    }

    @Before
    public void setup() throws IOException {
        createIndex("test");
        ensureGreen("test");

        index("test", "testtype", "1", jsonBuilder().startObject().field("text", "value1").endObject());
        index("test", "testtype", "2", jsonBuilder().startObject().field("text", "value2").endObject());
        refresh();
    }

    @Override
    public Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder().put(super.nodeSettings(nodeOrdinal))
                .put(Environment.PATH_CONF_SETTING.getKey(), this.getDataPath("config")).build();
    }

    public void testTemplateInBody() throws IOException {
        Map<String, Object> vars = new HashMap<>();
        vars.put("template", "all");

        TemplateQueryBuilder builder = new TemplateQueryBuilder(new Template("{\"match_{{template}}\": {}}\"", ScriptType.INLINE, null,
                null, vars));
        SearchResponse sr = client().prepareSearch().setQuery(builder)
                .execute().actionGet();
        assertHitCount(sr, 2);
    }

    public void testTemplateInBodyWithSize() throws IOException {
        Map<String, Object> params = new HashMap<>();
        params.put("template", "all");
        SearchResponse sr = client().prepareSearch()
                .setSource(
                        new SearchSourceBuilder().size(0).query(
                                QueryBuilders.templateQuery(new Template("{ \"match_{{template}}\": {} }",
                                        ScriptType.INLINE, null, null, params)))).execute()
                .actionGet();
        assertNoFailures(sr);
        assertThat(sr.getHits().hits().length, equalTo(0));
    }

    public void testTemplateWOReplacementInBody() throws IOException {
        Map<String, Object> vars = new HashMap<>();

        TemplateQueryBuilder builder = new TemplateQueryBuilder(new Template(
                "{\"match_all\": {}}\"", ScriptType.INLINE, null, null, vars));
        SearchResponse sr = client().prepareSearch().setQuery(builder)
                .execute().actionGet();
        assertHitCount(sr, 2);
    }

    public void testTemplateInFile() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("template", "all");

        TemplateQueryBuilder builder = new TemplateQueryBuilder(new Template(
                "storedTemplate", ScriptService.ScriptType.FILE, null, null, vars));
        SearchResponse sr = client().prepareSearch().setQuery(builder)
                .execute().actionGet();
        assertHitCount(sr, 2);
    }

    public void testRawFSTemplate() throws IOException {
        Map<String, Object> params = new HashMap<>();
        params.put("template", "all");
        TemplateQueryBuilder builder = new TemplateQueryBuilder(new Template("storedTemplate", ScriptType.FILE, null, null, params));
        SearchResponse sr = client().prepareSearch().setQuery(builder).get();
        assertHitCount(sr, 2);
    }

    public void testSearchRequestTemplateSource() throws Exception {
        SearchRequest searchRequest = new SearchRequest();
        searchRequest.indices("_all");

        String query = "{ \"inline\" : { \"query\": {\"match_{{template}}\": {} } }, \"params\" : { \"template\":\"all\" } }";
        SearchTemplateRequest request =  RestSearchTemplateAction.parse(new BytesArray(query));
        request.setRequest(searchRequest);
        SearchTemplateResponse response = client().execute(SearchTemplateAction.INSTANCE, request).get();
        assertHitCount(response.getResponse(), 2);
    }

    private Template parseTemplate(String template) throws IOException {
        try (XContentParser parser = XContentFactory.xContent(template).createParser(template)) {
            return TemplateQueryBuilder.parse(parser, ParseFieldMatcher.EMPTY, "params", "template");
        }
    }

    // Relates to #6318
    public void testSearchRequestFail() throws Exception {
        String query = "{ \"query\": {\"match_all\": {}}, \"size\" : \"{{my_size}}\"  }";

        SearchRequest searchRequest = new SearchRequest();
        searchRequest.indices("_all");

        expectThrows(Exception.class, () -> new SearchTemplateRequestBuilder(client())
                .setRequest(searchRequest)
                .setScript(query)
                .setScriptType(ScriptType.INLINE)
                .setScriptParams(randomBoolean() ? null : Collections.emptyMap())
                .get());

        SearchTemplateResponse searchResponse = new SearchTemplateRequestBuilder(client())
                .setRequest(searchRequest)
                .setScript(query)
                .setScriptType(ScriptType.INLINE)
                .setScriptParams(Collections.singletonMap("my_size", 1))
                .get();

        assertThat(searchResponse.getResponse().getHits().hits().length, equalTo(1));
    }

    public void testThatParametersCanBeSet() throws Exception {
        index("test", "type", "1", jsonBuilder().startObject().field("theField", "foo").endObject());
        index("test", "type", "2", jsonBuilder().startObject().field("theField", "foo 2").endObject());
        index("test", "type", "3", jsonBuilder().startObject().field("theField", "foo 3").endObject());
        index("test", "type", "4", jsonBuilder().startObject().field("theField", "foo 4").endObject());
        index("test", "type", "5", jsonBuilder().startObject().field("otherField", "foo").endObject());
        refresh();

        Map<String, Object> templateParams = new HashMap<>();
        templateParams.put("mySize", "2");
        templateParams.put("myField", "theField");
        templateParams.put("myValue", "foo");

        SearchTemplateResponse searchResponse = new SearchTemplateRequestBuilder(client())
                .setRequest(new SearchRequest("test").types("type"))
                .setScript("full-query-template")
                .setScriptType(ScriptType.FILE)
                .setScriptParams(templateParams)
                .get();
        assertHitCount(searchResponse.getResponse(), 4);
        // size kicks in here...
        assertThat(searchResponse.getResponse().getHits().getHits().length, is(2));

        templateParams.put("myField", "otherField");
        searchResponse = new SearchTemplateRequestBuilder(client())
                .setRequest(new SearchRequest("test").types("type"))
                .setScript("full-query-template")
                .setScriptType(ScriptType.FILE)
                .setScriptParams(templateParams)
                .get();
        assertHitCount(searchResponse.getResponse(), 1);
    }

    public void testSearchTemplateQueryFromFile() throws Exception {
        SearchRequest searchRequest = new SearchRequest();
        searchRequest.indices("_all");
        String query = "{" + "  \"file\": \"full-query-template\"," + "  \"params\":{" + "    \"mySize\": 2,"
                + "    \"myField\": \"text\"," + "    \"myValue\": \"value1\"" + "  }" + "}";
        SearchTemplateRequest request =  RestSearchTemplateAction.parse(new BytesArray(query));
        request.setRequest(searchRequest);
        SearchTemplateResponse searchResponse = client().execute(SearchTemplateAction.INSTANCE, request).get();
        assertThat(searchResponse.getResponse().getHits().hits().length, equalTo(1));
    }

    /**
     * Test that template can be expressed as a single escaped string.
     */
    public void testTemplateQueryAsEscapedString() throws Exception {
        SearchRequest searchRequest = new SearchRequest();
        searchRequest.indices("_all");
        String query = "{" + "  \"inline\" : \"{ \\\"size\\\": \\\"{{size}}\\\", \\\"query\\\":{\\\"match_all\\\":{}}}\","
                + "  \"params\":{" + "    \"size\": 1" + "  }" + "}";
        SearchTemplateRequest request =  RestSearchTemplateAction.parse(new BytesArray(query));
        request.setRequest(searchRequest);
        SearchTemplateResponse searchResponse = client().execute(SearchTemplateAction.INSTANCE, request).get();
        assertThat(searchResponse.getResponse().getHits().hits().length, equalTo(1));
    }

    /**
     * Test that template can contain conditional clause. In this case it is at
     * the beginning of the string.
     */
    public void testTemplateQueryAsEscapedStringStartingWithConditionalClause() throws Exception {
        SearchRequest searchRequest = new SearchRequest();
        searchRequest.indices("_all");
        String templateString = "{"
                + "  \"inline\" : \"{ {{#use_size}} \\\"size\\\": \\\"{{size}}\\\", {{/use_size}} \\\"query\\\":{\\\"match_all\\\":{}}}\","
                + "  \"params\":{" + "    \"size\": 1," + "    \"use_size\": true" + "  }" + "}";
        SearchTemplateRequest request =  RestSearchTemplateAction.parse(new BytesArray(templateString));
        request.setRequest(searchRequest);
        SearchTemplateResponse searchResponse = client().execute(SearchTemplateAction.INSTANCE, request).get();
        assertThat(searchResponse.getResponse().getHits().hits().length, equalTo(1));
    }

    /**
     * Test that template can contain conditional clause. In this case it is at
     * the end of the string.
     */
    public void testTemplateQueryAsEscapedStringWithConditionalClauseAtEnd() throws Exception {
        SearchRequest searchRequest = new SearchRequest();
        searchRequest.indices("_all");
        String templateString = "{"
                + "  \"inline\" : \"{ \\\"query\\\":{\\\"match_all\\\":{}} {{#use_size}}, \\\"size\\\": \\\"{{size}}\\\" {{/use_size}} }\","
                + "  \"params\":{" + "    \"size\": 1," + "    \"use_size\": true" + "  }" + "}";
        SearchTemplateRequest request =  RestSearchTemplateAction.parse(new BytesArray(templateString));
        request.setRequest(searchRequest);
        SearchTemplateResponse searchResponse = client().execute(SearchTemplateAction.INSTANCE, request).get();
        assertThat(searchResponse.getResponse().getHits().hits().length, equalTo(1));
    }

    public void testIndexedTemplateClient() throws Exception {
        assertAcked(client().admin().cluster().preparePutStoredScript()
                .setScriptLang(MustacheScriptEngineService.NAME)
                .setId("testTemplate")
                .setSource(new BytesArray("{" +
                "\"template\":{" +
                "                \"query\":{" +
                "                   \"match\":{" +
                "                    \"theField\" : \"{{fieldParam}}\"}" +
                "       }" +
                "}" +
                "}")));


        assertAcked(client().admin().cluster().preparePutStoredScript()
                .setScriptLang(MustacheScriptEngineService.NAME)
                .setId("testTemplate").setSource(new BytesArray("{" +
                "\"template\":{" +
                "                \"query\":{" +
                "                   \"match\":{" +
                "                    \"theField\" : \"{{fieldParam}}\"}" +
                "       }" +
                "}" +
                "}")));

        GetStoredScriptResponse getResponse = client().admin().cluster()
                .prepareGetStoredScript(MustacheScriptEngineService.NAME, "testTemplate").get();
        assertNotNull(getResponse.getStoredScript());

        List<IndexRequestBuilder> builders = new ArrayList<>();

        builders.add(client().prepareIndex("test", "type", "1").setSource("{\"theField\":\"foo\"}"));
        builders.add(client().prepareIndex("test", "type", "2").setSource("{\"theField\":\"foo 2\"}"));
        builders.add(client().prepareIndex("test", "type", "3").setSource("{\"theField\":\"foo 3\"}"));
        builders.add(client().prepareIndex("test", "type", "4").setSource("{\"theField\":\"foo 4\"}"));
        builders.add(client().prepareIndex("test", "type", "5").setSource("{\"theField\":\"bar\"}"));

        indexRandom(true, builders);

        Map<String, Object> templateParams = new HashMap<>();
        templateParams.put("fieldParam", "foo");

        SearchTemplateResponse searchResponse = new SearchTemplateRequestBuilder(client())
                .setRequest(new SearchRequest("test").types("type"))
                .setScript("testTemplate").setScriptType(ScriptType.STORED).setScriptParams(templateParams)
                .get();
        assertHitCount(searchResponse.getResponse(), 4);

        assertAcked(client().admin().cluster()
                .prepareDeleteStoredScript(MustacheScriptEngineService.NAME, "testTemplate"));

        getResponse = client().admin().cluster()
                .prepareGetStoredScript(MustacheScriptEngineService.NAME, "testTemplate").get();
        assertNull(getResponse.getStoredScript());

        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> new SearchTemplateRequestBuilder(client())
                .setRequest(new SearchRequest("test").types("type"))
                .setScript("/template_index/mustache/1000").setScriptType(ScriptType.STORED).setScriptParams(templateParams)
                .get());
        assertThat(e.getMessage(), containsString("Illegal index script format [/template_index/mustache/1000] should be /lang/id"));
    }

    public void testIndexedTemplate() throws Exception {
        assertAcked(client().admin().cluster().preparePutStoredScript()
                .setScriptLang(MustacheScriptEngineService.NAME)
                .setId("1a")
                .setSource(new BytesArray("{" +
                                "\"template\":{"+
                                "                \"query\":{" +
                                "                   \"match\":{" +
                                "                    \"theField\" : \"{{fieldParam}}\"}" +
                                "       }" +
                                "}" +
                                "}"
                ))
        );
        assertAcked(client().admin().cluster().preparePutStoredScript()
                .setScriptLang(MustacheScriptEngineService.NAME)
                .setId("2")
                .setSource(new BytesArray("{" +
                        "\"template\":{"+
                        "                \"query\":{" +
                        "                   \"match\":{" +
                        "                    \"theField\" : \"{{fieldParam}}\"}" +
                        "       }" +
                        "}" +
                        "}"))
        );
        assertAcked(client().admin().cluster().preparePutStoredScript()
                .setScriptLang(MustacheScriptEngineService.NAME)
                .setId("3")
                .setSource(new BytesArray("{" +
                        "\"template\":{"+
                        "             \"match\":{" +
                        "                    \"theField\" : \"{{fieldParam}}\"}" +
                        "       }" +
                        "}"))
        );

        List<IndexRequestBuilder> builders = new ArrayList<>();
        builders.add(client().prepareIndex("test", "type", "1").setSource("{\"theField\":\"foo\"}"));
        builders.add(client().prepareIndex("test", "type", "2").setSource("{\"theField\":\"foo 2\"}"));
        builders.add(client().prepareIndex("test", "type", "3").setSource("{\"theField\":\"foo 3\"}"));
        builders.add(client().prepareIndex("test", "type", "4").setSource("{\"theField\":\"foo 4\"}"));
        builders.add(client().prepareIndex("test", "type", "5").setSource("{\"theField\":\"bar\"}"));
        indexRandom(true, builders);

        Map<String, Object> templateParams = new HashMap<>();
        templateParams.put("fieldParam", "foo");

        SearchTemplateResponse searchResponse = new SearchTemplateRequestBuilder(client())
                .setRequest(new SearchRequest().indices("test").types("type"))
                .setScript("/mustache/1a")
                .setScriptType(ScriptType.STORED)
                .setScriptParams(templateParams)
                .get();
        assertHitCount(searchResponse.getResponse(), 4);

        expectThrows(IllegalArgumentException.class, () -> new SearchTemplateRequestBuilder(client())
                .setRequest(new SearchRequest().indices("test").types("type"))
                .setScript("/template_index/mustache/1000")
                .setScriptType(ScriptType.STORED)
                .setScriptParams(templateParams)
                .get());

        expectThrows(IllegalArgumentException.class, () ->  new SearchTemplateRequestBuilder(client())
                    .setRequest(new SearchRequest().indices("test").types("type"))
                    .setScript("/myindex/mustache/1")
                    .setScriptType(ScriptType.STORED)
                    .setScriptParams(templateParams)
                    .get());

        templateParams.put("fieldParam", "bar");
        searchResponse = new SearchTemplateRequestBuilder(client())
                .setRequest(new SearchRequest("test").types("type"))
                .setScript("/mustache/2").setScriptType(ScriptService.ScriptType.STORED).setScriptParams(templateParams)
                .get();
        assertHitCount(searchResponse.getResponse(), 1);

        Map<String, Object> vars = new HashMap<>();
        vars.put("fieldParam", "bar");

        TemplateQueryBuilder builder = new TemplateQueryBuilder(new Template(
                "3", ScriptService.ScriptType.STORED, null, null, vars));
        SearchResponse sr = client().prepareSearch().setQuery(builder)
                .execute().actionGet();
        assertHitCount(sr, 1);

        // "{\"template\": {\"id\": \"3\",\"params\" : {\"fieldParam\" : \"foo\"}}}";
        Map<String, Object> params = new HashMap<>();
        params.put("fieldParam", "foo");
        TemplateQueryBuilder templateQuery = new TemplateQueryBuilder(new Template("3", ScriptType.STORED, null, null, params));
        sr = client().prepareSearch().setQuery(templateQuery).get();
        assertHitCount(sr, 4);

        templateQuery = new TemplateQueryBuilder(new Template("/mustache/3", ScriptType.STORED, null, null, params));
        sr = client().prepareSearch().setQuery(templateQuery).get();
        assertHitCount(sr, 4);
    }

    // Relates to #10397
    public void testIndexedTemplateOverwrite() throws Exception {
        createIndex("testindex");
        ensureGreen("testindex");

        index("testindex", "test", "1", jsonBuilder().startObject().field("searchtext", "dev1").endObject());
        refresh();

        int iterations = randomIntBetween(2, 11);
        for (int i = 1; i < iterations; i++) {
            assertAcked(client().admin().cluster().preparePutStoredScript()
            .setScriptLang(MustacheScriptEngineService.NAME)
                    .setId("git01")
                    .setSource(new BytesArray("{\"template\":{\"query\": {\"match\": {\"searchtext\": {\"query\": \"{{P_Keyword1}}\"," +
                            "\"type\": \"ooophrase_prefix\"}}}}}")));

            GetStoredScriptResponse getResponse = client().admin().cluster()
                    .prepareGetStoredScript(MustacheScriptEngineService.NAME, "git01").get();
            assertNotNull(getResponse.getStoredScript());

            Map<String, Object> templateParams = new HashMap<>();
            templateParams.put("P_Keyword1", "dev");

            ParsingException e = expectThrows(ParsingException.class, () -> new SearchTemplateRequestBuilder(client())
                    .setRequest(new SearchRequest("testindex").types("test"))
                    .setScript("git01").setScriptType(ScriptService.ScriptType.STORED).setScriptParams(templateParams)
                    .get());
            assertThat(e.getMessage(), containsString("[match] query does not support type ooophrase_prefix"));

            assertAcked(client().admin().cluster().preparePutStoredScript()
                    .setScriptLang(MustacheScriptEngineService.NAME)
                    .setId("git01")
                    .setSource(new BytesArray("{\"query\": {\"match\": {\"searchtext\": {\"query\": \"{{P_Keyword1}}\"," +
                            "\"type\": \"phrase_prefix\"}}}}")));

            SearchTemplateResponse searchResponse = new SearchTemplateRequestBuilder(client())
                    .setRequest(new SearchRequest("testindex").types("test"))
                    .setScript("git01").setScriptType(ScriptService.ScriptType.STORED).setScriptParams(templateParams)
                    .get();
            assertHitCount(searchResponse.getResponse(), 1);
        }
    }

    public void testIndexedTemplateWithArray() throws Exception {
      String multiQuery = "{\"query\":{\"terms\":{\"theField\":[\"{{#fieldParam}}\",\"{{.}}\",\"{{/fieldParam}}\"]}}}";

      assertAcked(
              client().admin().cluster().preparePutStoredScript()
                .setScriptLang(MustacheScriptEngineService.NAME)
                .setId("4")
                .setSource(jsonBuilder().startObject().field("template", multiQuery).endObject().bytes())
      );
      List<IndexRequestBuilder> builders = new ArrayList<>();
      builders.add(client().prepareIndex("test", "type", "1").setSource("{\"theField\":\"foo\"}"));
      builders.add(client().prepareIndex("test", "type", "2").setSource("{\"theField\":\"foo 2\"}"));
      builders.add(client().prepareIndex("test", "type", "3").setSource("{\"theField\":\"foo 3\"}"));
      builders.add(client().prepareIndex("test", "type", "4").setSource("{\"theField\":\"foo 4\"}"));
      builders.add(client().prepareIndex("test", "type", "5").setSource("{\"theField\":\"bar\"}"));

      indexRandom(true,builders);

      Map<String, Object> arrayTemplateParams = new HashMap<>();
      String[] fieldParams = {"foo","bar"};
      arrayTemplateParams.put("fieldParam", fieldParams);

        SearchTemplateResponse searchResponse = new SearchTemplateRequestBuilder(client())
                .setRequest(new SearchRequest("test").types("type"))
                .setScript("/mustache/4").setScriptType(ScriptService.ScriptType.STORED).setScriptParams(arrayTemplateParams)
                .get();
        assertHitCount(searchResponse.getResponse(), 5);
    }

}
