package com.smartsheet.wiremock.extensions;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.Errors;
import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.common.TextFile;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformer;
import com.github.tomakehurst.wiremock.http.*;
import joptsimple.internal.Strings;
import org.json.JSONObject;
import org.skyscreamer.jsonassert.JSONCompare;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.skyscreamer.jsonassert.JSONCompareResult;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class ApiScenarioTransformer extends ResponseDefinitionTransformer {
	private static final String SCENARIO_HEADER_NAME = "Api-Scenario";
	private static final String CONTENT_TYPE_HEADER_NAME = "Content-Type";
	private static final String JSON_MIME_TYPE = "application/json";
	private static final String SCENARIO_SCENARIO_FIELD = "scenario";
	private static final String SCENARIO_QUERY_PARAMETERS_FIELD = "queryParameters";
	private static final String SCENARIO_REQUEST_FIELD = "request";
	private static final String SCENARIO_BODY_FIELD = "body";
	private static final String SCENARIO_URL_PATH_FIELD = "urlPath";
	private static final String SCENARIO_HEADERS_FIELD = "headers";
	private static final String SCENARIOS_DIR = "__scenarios";
	private static final String SCENARIOS_FILE = "scenarios.json";
	private static final Integer SMARTSHEET_ERROR_CODE = 9999;
	private static final String SMARTSHEET_REF_ID = "123abc";

	private static final ResponseDefinition INVALID_SCENARIO_RESPONSE =
			new ResponseDefinitionBuilder()
					.withStatus(404)
					.withStatusMessage("Not Found")
					.withHeader(CONTENT_TYPE_HEADER_NAME, JSON_MIME_TYPE)
					.withBody(ErrorBody.forMessage("No scenario provided", SMARTSHEET_ERROR_CODE, SMARTSHEET_REF_ID))
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
				.withHeader(CONTENT_TYPE_HEADER_NAME, JSON_MIME_TYPE)
				.withBody(ErrorBody.forMessage(diff, SMARTSHEET_ERROR_CODE, SMARTSHEET_REF_ID))
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

		return scenarioHeader.isPresent() && scenarioHeader.isSingleValued();

	}

	private ResponseDefinition buildUnknownScenarioResponse(Request request) {
		String scenarioName = getScenarioName (request);
		return new ResponseDefinitionBuilder()
				.withStatus(404)
				.withStatusMessage("Not Found")
				.withHeader(CONTENT_TYPE_HEADER_NAME, JSON_MIME_TYPE)
				.withBody(ErrorBody.forMessage("No scenario exists with provided name: " + scenarioName, SMARTSHEET_ERROR_CODE, SMARTSHEET_REF_ID))
				.build();
	}

	public static final class ErrorBody {
		private final String message;
		private final Integer errorCode;
		private final String refId;

		@JsonCreator
		ErrorBody(@JsonProperty ("message") String message, @JsonProperty ("errorCode") Integer errorCode, @JsonProperty ("refId") String refId) {
			this.message = message;
			this.errorCode = errorCode;
			this.refId = refId;
		}

		public String getMessage() {
			return message;
		}
		public Integer getErrorCode () { return errorCode; }
		public String getRefId () { return refId; }

		@JsonIgnore
		String toJson() {
			return Json.write(this);
		}

		static String forMessage(String message, Integer errorCode, String refId) {
			return new ErrorBody(message, errorCode, refId).toJson();
		}
	}

	private static class RequestDiff {
		private static String getDiff(Request request, JsonNode scenario) {
			return String.format("%s %s %s %s",
					diffHeaders(request, scenario),
					diffUrl(request, scenario),
					diffQueryParams(request, scenario),
					diffBody(request, scenario)).trim();
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
			JsonNode scenarioURL = scenario.get(SCENARIO_REQUEST_FIELD).get(SCENARIO_URL_PATH_FIELD);
			if(scenarioURL == null){
				return "Test scenario's request URL path was not defined or failed to parse.";
			}
			String scenarioURLString = scenarioURL.textValue();

			String requestURLString = request.getUrl().split("\\?")[0];

			if (requestURLString.equals(scenarioURLString)) {
				return "";
			}

			return formatAssert("URL Match", scenarioURLString, requestURLString);
		}

		private static String diffQueryParams(Request request, JsonNode scenario) {
			JsonNode scenarioQueryParams = scenario.get(SCENARIO_REQUEST_FIELD).get(SCENARIO_QUERY_PARAMETERS_FIELD);
			if(scenarioQueryParams == null) {
				return "";
			}

			return String.format("%s %s",diffExpectedQueryParams(request, scenarioQueryParams), diffUnexpectedQueryParams(request, scenarioQueryParams)).trim();

		}

		private static String diffHeaders(Request request, JsonNode scenario) {
			JsonNode scenarioHeaders = scenario.get(SCENARIO_REQUEST_FIELD).get(SCENARIO_HEADERS_FIELD);

			if(scenarioHeaders == null){
				return "";
			}
			return diffExpectedHeaders(request, scenarioHeaders);
		}

		private static String formatAssert(String assertLabel, String expected, String actual) {

			return String.format("%s Expected: %s Got: %s ", assertLabel, expected, actual);
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
				return String.format("Headers: Expected %s, but not found. ", header.getKey());
			}

			String headerValue = header.getValue().asText();
			String requestValue = request.getHeader(header.getKey());

			if (!headerValue.equals(requestValue)) {
				return formatAssert("Headers:" + header.getKey(), headerValue, requestValue);
			}

			return "";
		}

		private static String diffExpectedQueryParams(Request request, JsonNode scenarioQueryParams) {
			Iterator<Map.Entry<String, JsonNode>> scenarioQueryParamIterator = scenarioQueryParams.fields();

			StringBuilder queryParamDiff = new StringBuilder();
			while(scenarioQueryParamIterator.hasNext()) {
				Map.Entry<String, JsonNode> queryParam = scenarioQueryParamIterator.next();

				queryParamDiff.append(diffExpectedQueryParam(request, queryParam));
			}

			return queryParamDiff.toString();
		}

		private static String diffExpectedQueryParam(Request request, Map.Entry<String, JsonNode> queryParam) {
			QueryParameter requestParam = request.queryParameter(queryParam.getKey());
			if(requestParam == null || requestParam.key() == null || requestParam.key().isEmpty()){
				return String.format("Expected Query Parameters: Expected %s, but not found. ", queryParam.getKey());
			}

			if (!requestParam.containsValue(queryParam .getValue().asText())) {
				return formatAssert("Expected Query Parameter:" + queryParam.getKey(), queryParam.getValue().asText(), requestParam.firstValue());
			}

			return "";
		}


		private static String diffUnexpectedQueryParams(Request request, JsonNode scenarioQueryParams) {

			StringBuilder queryParamDiff = new StringBuilder();
			List<String> requestQueryParamKeys = getRequestQueryParameterKeys(request);
			List<String> scenarioQueryParamKeys = getScenarioQueryParameterKeys(scenarioQueryParams);

			for(String requestParamKey : requestQueryParamKeys) {
				if (!scenarioQueryParamKeys.contains(requestParamKey.trim())) {
					queryParamDiff.append(String.format("Query Parameters: Request contained '%s', but was not expected. ", requestParamKey));
				}
			}
            return queryParamDiff.toString();
		}

		private static List<String> getRequestQueryParameterKeys(Request request) {

			List<String> requestQueryParamKeys = new ArrayList<>();
			String[] requestQueryParamStrings = request.getUrl().split("\\?");
			String requestQueryParamString = null;
			if(requestQueryParamStrings.length == 2){
				requestQueryParamString = requestQueryParamStrings[1];
			}
			if(requestQueryParamString == null || requestQueryParamString.isEmpty()){
				return requestQueryParamKeys;
			}

			//Populate list of query parameter keys found in request.
			String[] queryParamStrings = requestQueryParamString.split("\\&");
			for (String queryParam : queryParamStrings) {
				String[] queryParamString = queryParam.split("\\=");
				requestQueryParamKeys.add(queryParamString[0].trim());
			}
			return requestQueryParamKeys;
		}

		private static List<String> getScenarioQueryParameterKeys(JsonNode scenarioQueryParams) {
			//Populate list of scenario expected query parameter keys.
			Iterator<Map.Entry<String, JsonNode>> scenarioQueryParamIterator = scenarioQueryParams.fields();
			List<String> scenarioQueryParamKeys = new ArrayList<>();
			while (scenarioQueryParamIterator.hasNext()) {
				Map.Entry<String, JsonNode> scenarioQueryParam = scenarioQueryParamIterator.next();
				scenarioQueryParamKeys.add(scenarioQueryParam.getKey().trim());
			}
			return scenarioQueryParamKeys;
		}
	}
}
