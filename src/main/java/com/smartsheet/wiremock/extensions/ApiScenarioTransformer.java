package com.smartsheet.wiremock.extensions;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.common.TextFile;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformer;
import com.github.tomakehurst.wiremock.http.*;
import joptsimple.internal.Strings;
import org.skyscreamer.jsonassert.JSONCompare;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.skyscreamer.jsonassert.JSONCompareResult;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

public class ApiScenarioTransformer extends ResponseDefinitionTransformer {
	private static final String SCENARIO_HEADER_NAME = "Api-Scenario";
	private static final String CONTENT_TYPE_HEADER_NAME = "Content-Type";
	private static final String JSON_MIME_TYPE = "application/json";
	private static final String SCENARIO_SCENARIO_FIELD = "scenario";
	private static final String SCENARIO_REQUEST_FIELD = "request";
	private static final String SCENARIO_BODY_FIELD = "body";
	private static final String SCENARIO_URL_PATH_FIELD = "urlPath";
	private static final String SCENARIO_HEADERS_FIELD = "headers";
	private static final String SCENARIOS_DIR = "__scenarios";
	private static final String SCENARIOS_FILE = "scenarios.json";

	private static final ResponseDefinition INVALID_SCENARIO_RESPONSE =
			new ResponseDefinitionBuilder()
					.withStatus(404)
					.withStatusMessage("Not Found")
					.withHeader(CONTENT_TYPE_HEADER_NAME, JSON_MIME_TYPE)
					.withBody(ErrorBody.forMessage("No scenario provided"))
					.build();

	@Override
	public String getName() {
		return "Sample";
	}

	@Override
	public ResponseDefinition transform(
			Request request,
			ResponseDefinition responseDefinition,
			FileSource files,
			Parameters parameters) {

		if (isMatched(responseDefinition)) return responseDefinition;
		if (!scenarioHeaderIsValid(request)) return INVALID_SCENARIO_RESPONSE;

		JsonNode scenario = getScenario(request, files);
		if (scenario == null) return buildUnknownScenarioResponse(request);

		return getDiffResponse(request, scenario);
	}

	private boolean isMatched(ResponseDefinition responseDefinition) {
		return responseDefinition.getStatus() != 404;
	}

	private ResponseDefinition getDiffResponse(Request request, JsonNode scenario) {
		String diff = RequestDiff.getDiff(request, scenario);

		return new ResponseDefinitionBuilder()
				.withStatus(400)
				.withStatusMessage("Bad Request")
				.withBody(ErrorBody.forMessage(diff))
				.build();
	}

	private String getScenarioName(Request request) {
		HttpHeader scenarioHeader = request.header(SCENARIO_HEADER_NAME);

		return scenarioHeader.firstValue();
	}

	private JsonNode getScenario(Request request, FileSource files) {
		ArrayNode scenarios = getScenarios(files);

		String scenarioName = getScenarioName(request);

		for (JsonNode scenario : scenarios) {
			if (scenario.get(SCENARIO_SCENARIO_FIELD).textValue().equals(scenarioName)) return scenario;
		}

		return null;
	}

	private ArrayNode getScenarios(FileSource files) {
		TextFile scenarioFile = files.child(SCENARIOS_DIR).getTextFileNamed(SCENARIOS_FILE);

		ObjectMapper mapper = new ObjectMapper();
		JsonNode scenarios;
		try {
			scenarios = mapper.readTree(scenarioFile.readContentsAsString());
		} catch (IOException e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}

		return (ArrayNode)scenarios;
	}

	private static boolean scenarioHeaderIsValid(Request request) {
		HttpHeader scenarioHeader = request.header(SCENARIO_HEADER_NAME);

		if(!scenarioHeader.isPresent()) return false;

		if(!scenarioHeader.isSingleValued()) return false;

		return true;
	}

	private static ResponseDefinition buildMalformedScenarioResponse(HttpHeader scenarioHeader){
		String scenarios = Strings.join(scenarioHeader.values(), ",");
		String body = ErrorBody.forMessage("Invalid Scenario: " + scenarios);

		return new ResponseDefinitionBuilder()
				.withStatus(400)
				.withStatusMessage("Bad Request")
				.withHeader(CONTENT_TYPE_HEADER_NAME, JSON_MIME_TYPE)
				.withBody(body)
				.build();
	}

	private ResponseDefinition buildUnknownScenarioResponse(Request request) {
		String scenarioName = getScenarioName (request);
		return new ResponseDefinitionBuilder()
				.withStatus(404)
				.withStatusMessage("Not Found")
				.withHeader(CONTENT_TYPE_HEADER_NAME, JSON_MIME_TYPE)
				.withBody(ErrorBody.forMessage("No scenario exists with provided name: " + scenarioName))
				.build();
	}

	public static final class ErrorBody {
		private final String message;

		@JsonCreator
		ErrorBody(@JsonProperty ("message") String message) {
			this.message = message;
		}

		public String getMessage() {
			return message;
		}

		@JsonIgnore
		String toJson() {
			return Json.write(this);
		}

		static String forMessage(String message) {
			return new ErrorBody(message).toJson();
		}
	}

	private static class RequestDiff {
		private static String getDiff(Request request, JsonNode scenario) {
			return diffHeaders(request, scenario) +
					diffUrl(request, scenario) +
					diffBody(request, scenario);
		}

		private static String diffBody(Request request, JsonNode scenario) {

			if(request.getMethod().toString().toUpperCase() == "GET"){
				return "";
			}

			JsonNode scenarioBody = scenario.get(SCENARIO_REQUEST_FIELD).get(SCENARIO_BODY_FIELD);
			if( scenarioBody == null){
				return "Test scenario's request body was not defined or failed to parse.";
			}

			JSONCompareResult result;
			try {
				result = JSONCompare.compareJSON(
						scenarioBody.toString(),
						request.getBodyAsString(),
						JSONCompareMode.STRICT_ORDER
				);
			} catch (org.json.JSONException e) {
				e.printStackTrace();
				throw new RuntimeException(e);
			}

			return result.getMessage();
		}

		private static String diffUrl(Request request, JsonNode scenario) {
			JsonNode scenarioRequest = scenario.get(SCENARIO_REQUEST_FIELD).get(SCENARIO_URL_PATH_FIELD);
			if(scenarioRequest == null){
				return "Test scenario's request URL path was not defined or failed to parse.";
			}
			String scenarioRequestString = scenarioRequest.textValue();
			if (request.getUrl().equals(scenarioRequestString)) {
				return "";
			}

			return formatAssert("URL", scenarioRequestString, request.getUrl());
		}

		private static String formatAssert(String assertLabel, String expected, String actual) {
			return String.format("%s \nExpected: %s \n     got: %s \n", assertLabel, expected, actual);
		}

		private static String diffHeaders(Request request, JsonNode scenario) {
			JsonNode scenarioHeaders = scenario.get(SCENARIO_REQUEST_FIELD).get(SCENARIO_HEADERS_FIELD);

			if(scenarioHeaders == null){
				return "";
			}
			return diffExpectedHeaders(request, scenarioHeaders);// + diffUnexpectedHeaders(request, scenarioHeaders);
		}

		private static String diffUnexpectedHeaders(Request request, JsonNode scenarioHeaders) {
			StringBuilder unexpectedHeaderDiff = new StringBuilder();
			for (HttpHeader header : request.getHeaders().all()) {
				if (!scenarioHeaders.has(header.key())) {
					unexpectedHeaderDiff.append(String.format("headers: Contains %s, but not expected\n", header.key()));
				}
			}

			return unexpectedHeaderDiff.toString();
		}

		private static String diffExpectedHeaders(Request request, JsonNode scenarioHeaders) {
			Iterator<Map.Entry<String, JsonNode>> scenarioHeaderIterator = scenarioHeaders.fields();

			StringBuilder headerDiff = new StringBuilder();
			while(scenarioHeaderIterator.hasNext()) {
				Map.Entry<String, JsonNode> header = scenarioHeaderIterator.next();

				headerDiff.append(diffExpectedHeader(request, header));
			}

			return headerDiff.toString();
		}

		private static String diffExpectedHeader(Request request, Map.Entry<String, JsonNode> header) {
			if (!request.containsHeader(header.getKey())) {
				return String.format("headers: Expected %s, but not found\n", header.getKey());
			}

			String headerValue = header.getValue().asText();
			String requestValue = request.getHeader(header.getKey());

			if (!headerValue.equals(requestValue)) {
				return formatAssert("headers - " + header.getKey(), headerValue, requestValue);
			}

			return "";
		}
	}
}
