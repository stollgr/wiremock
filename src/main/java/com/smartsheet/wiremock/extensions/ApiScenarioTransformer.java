package com.smartsheet.wiremock.extensions;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformer;
import com.github.tomakehurst.wiremock.http.*;
import joptsimple.internal.Strings;

import java.io.File;
import java.io.IOException;

public class ApiScenarioTransformer extends ResponseDefinitionTransformer {
	private static final String SCENARIO_HEADER_NAME = "Api-Scenario";
	private static final String CONTENT_TYPE_HEADER_NAME = "Content-Type";
	private static final String JSON_MIME_TYPE = "application/json";
	private static final String SCENARIO_FIELD = "scenario";

	private static final ResponseDefinition INVALID_SCENARIO_RESPONSE =
			new ResponseDefinitionBuilder()
					.withStatus(404)
					.withStatusMessage("Not Found")
					.withHeader(CONTENT_TYPE_HEADER_NAME, JSON_MIME_TYPE)
					.withBody(ErrorBody.forMessage("No scenario provided"))
					.build();

	private static final ResponseDefinition UNKNOWN_SCENARIO_RESPONSE =
			new ResponseDefinitionBuilder()
					.withStatus(404)
					.withStatusMessage("Not Found")
					.withHeader(CONTENT_TYPE_HEADER_NAME, JSON_MIME_TYPE)
					.withBody(ErrorBody.forMessage("No scenario exists with provided name"))
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

		if (isNotMatched(responseDefinition)) return responseDefinition;
		if (!scenarioHeaderIsValid(request)) return INVALID_SCENARIO_RESPONSE;

		JsonNode scenario = getScenario(request);
		if (scenario == null) return UNKNOWN_SCENARIO_RESPONSE;

		return getDiffResponse(request, scenario);
	}

	private boolean isNotMatched(ResponseDefinition responseDefinition) {
		return responseDefinition.getStatus() == 404;
	}

	private ResponseDefinition getDiffResponse(Request request, JsonNode scenario) {
		String diff = getDiff(request, scenario);

		return new ResponseDefinitionBuilder()
				.withStatus(400)
				.withStatusMessage("Unprocessable Entity")
				.withBody(ErrorBody.forMessage(diff))
				.build();
	}



	private String getDiff(Request request, JsonNode scenario) {
		return "It is different!!";
		//if matched begin diff
		//provide error of diff mismatch
	}

	private String getScenarioName(Request request) {
		HttpHeader scenarioHeader = request.header(SCENARIO_HEADER_NAME);

		return scenarioHeader.firstValue();
	}

	private JsonNode getScenario(Request request) {
		ArrayNode scenarios = getScenarios();

		String scenarioName = getScenarioName(request);

		for (JsonNode scenario : scenarios) {
			if (scenario.get(SCENARIO_FIELD).textValue().equals(scenarioName)) return scenario;
		}

		return null;
	}

	private ArrayNode getScenarios() {
		ObjectMapper mapper = new ObjectMapper();
		JsonNode scenarios;
		try {
			scenarios = mapper.readTree(new File("./build/resources/main/testScenarios/scenarios.json"));
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
}
