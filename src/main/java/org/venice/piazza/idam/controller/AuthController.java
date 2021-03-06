/**
 * Copyright 2016, RadiantBlue Technologies, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/
package org.venice.piazza.idam.controller;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.web.bind.annotation.*;
import org.venice.piazza.idam.authn.PiazzaAuthenticator;
import org.venice.piazza.idam.authz.Authorizer;
import org.venice.piazza.idam.authz.endpoint.EndpointAuthorizer;
import org.venice.piazza.idam.authz.throttle.ThrottleAuthorizer;
import org.venice.piazza.idam.data.MongoAccessor;
import org.venice.piazza.idam.model.authz.AuthorizationException;

import model.logger.AuditElement;
import model.logger.Severity;
import model.response.AuthResponse;
import model.response.ErrorResponse;
import model.response.PiazzaResponse;
import model.response.SuccessResponse;
import model.response.UUIDResponse;
import model.security.authz.AuthorizationCheck;
import model.security.authz.UserProfile;
import util.PiazzaLogger;
import util.UUIDFactory;

/**
 * Controller that handles the User and Role requests for security information.
 * 
 * @author Russell.Orf
 */
@RestController
public class AuthController {
	@Autowired
	private PiazzaLogger pzLogger;
	@Autowired
	private MongoAccessor mongoAccessor;
	@Autowired
	private PiazzaAuthenticator piazzaAuthenticator;
	@Autowired
	private UUIDFactory uuidFactory;
	@Autowired
	private HttpServletRequest request;
	@Autowired
	private ThrottleAuthorizer throttleAuthorizer;
	@Autowired
	private EndpointAuthorizer endpointAuthorizer;

	private static final Logger LOGGER = LoggerFactory.getLogger(AuthController.class);
	private static final String IDAM_COMPONENT_NAME = "IDAM";
	private List<Authorizer> authorizers = new ArrayList<Authorizer>();

	/**
	 * Collects all of the Authorizers into a list that can be iterated through for a specific authorization check.
	 */
	@PostConstruct
	public void initializeAuthorizers() {
		authorizers.clear();
		authorizers.add(endpointAuthorizer);
		authorizers.add(throttleAuthorizer);
	}

	/**
	 * Verifies the provided username and credential from the header.
	 *
	 * @return Object JSON object that provides the authentication result. 
	 */
	@RequestMapping(value = "/authentication", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<PiazzaResponse> authenticateCredentials() {
		try {
			String headerValue = request.getHeader("Authorization");
			AuthResponse authResponse = null;
			String username = null;

			if (headerValue != null) {
				String[] headerParts = headerValue.split(" ");

				if (headerParts.length == 2) {

					String decodedAuthNInfo = new String(Base64.getDecoder().decode(headerParts[1]), StandardCharsets.UTF_8);

					// PKI Auth
					if (decodedAuthNInfo.split(":").length == 1) {
						authResponse = piazzaAuthenticator.getAuthenticationDecision(decodedAuthNInfo.split(":")[0]);
					}

					// BASIC Auth
					else if (decodedAuthNInfo.split(":").length == 2) {
						String[] decodedUserPassParts = decodedAuthNInfo.split(":");
						username = decodedUserPassParts[0];
						String credential = decodedUserPassParts[1];
						authResponse = piazzaAuthenticator.getAuthenticationDecision(username, credential);
					}

					if (authResponse != null && authResponse.getIsAuthSuccess()) {
						// Return the Key
						pzLogger.log("Successfully authenticated.", Severity.INFORMATIONAL,
								new AuditElement(username, "authenticateSuccess", ""));
						return new ResponseEntity<>(authResponse, HttpStatus.OK);
					}
				}
			}

			String error = "Authentication failed for user " + username;
			pzLogger.log(error, Severity.INFORMATIONAL, new AuditElement(username, "failedToAuthenticateUser", ""));
			return new ResponseEntity<>(new ErrorResponse(error, IDAM_COMPONENT_NAME), HttpStatus.UNAUTHORIZED);
		} catch (Exception exception) {
			String error = String.format("Error authenticating user: %s", exception.getMessage());
			LOGGER.error(error, exception);
			pzLogger.log(error, Severity.ERROR);
			return new ResponseEntity<>(new ErrorResponse(error, IDAM_COMPONENT_NAME), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Verifies that an API key is valid. Authentication.
	 * 
	 * @param body
	 *            A JSON object containing the 'uuid' field.
	 * 
	 * @return AuthResponse object containing the verification boolean of true or false
	 */
	@RequestMapping(value = "/authn", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<AuthResponse> authenticateApiKey(@RequestBody Map<String, String> body) {
		try {
			String uuid = body.get("uuid");
			if (uuid != null) {
				if (mongoAccessor.isApiKeyValid(uuid)) {
					// Look up the user profile
					UserProfile userProfile = mongoAccessor.getUserProfileByApiKey(uuid);
					pzLogger.log("Verified API Key.", Severity.INFORMATIONAL,
							new AuditElement(userProfile.getUsername(), "verifiedApiKey", ""));
					// Send back the success
					return new ResponseEntity<>(new AuthResponse(true, userProfile), HttpStatus.OK);
				} else {
					// Record the error
					pzLogger.log("Unable to verify API Key.", Severity.INFORMATIONAL,
							new AuditElement("idam", "failedToVerifyApiKey", uuid));
					return new ResponseEntity<>(new AuthResponse(false), HttpStatus.UNAUTHORIZED);
				}

			} else {
				pzLogger.log("Received a null API Key during verification.", Severity.INFORMATIONAL);
				return new ResponseEntity<>(new AuthResponse(false, "API Key is null."), HttpStatus.BAD_REQUEST);
			}
		} catch (Exception exception) {
			String error = String.format("Error authenticating UUID: %s", exception.getMessage());
			LOGGER.error(error, exception);
			pzLogger.log(error, Severity.ERROR);
			return new ResponseEntity<>(new AuthResponse(false, error), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Authorization check. Parameters define the username requesting an action.
	 * 
	 * @param authCheck
	 *            The model holding the username and the action
	 * @return Auth response. This contains the Boolean which determines if the user is able to perform the specified
	 *         action, and additional information for details of the check.
	 */
	@RequestMapping(value = "/authz", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<AuthResponse> authenticateAndAuthorize(@RequestBody AuthorizationCheck authorizationCheck) {
		try {
			// Log the check
			pzLogger.log("Checking Authorization for Action.", Severity.INFORMATIONAL,
					new AuditElement(authorizationCheck.getUsername(), "authorizationCheckForAction", authorizationCheck.toString()));

			// If the API Key is specified, then also perform authentication before doing the authorization check.
			// Check the validity of the API Key
			if (authorizationCheck.getApiKey() != null) {
				if (!mongoAccessor.isApiKeyValid(authorizationCheck.getApiKey())) {
					throw new AuthorizationException("Failed to Authenticate.", new AuthResponse(false, "Invalid API Key."));
				} else {
					// If the API Key was specified, but the user name was not, then populate the username so that the
					// Authorizers below can function.
					if (authorizationCheck.getUsername() == null) {
						authorizationCheck.setUsername(mongoAccessor.getUsername(authorizationCheck.getApiKey()));
					}
				}
				// Ensure the API Key matches the Payload
				if (!mongoAccessor.getUsername(authorizationCheck.getApiKey()).equals(authorizationCheck.getUsername())) {
					throw new AuthorizationException("Failed to Authenticate.",
							new AuthResponse(false, "API Key identity does not match the authorization check username."));
				}
			} else {
				// If the user specifies neither an API Key or a Username, then the request parameters are insufficient.
				if ((authorizationCheck.getUsername() == null) || (authorizationCheck.getUsername().isEmpty())) {
					throw new AuthorizationException("Incomplete request details",
							new AuthResponse(false, "API Key or Username not specified."));
				}
			}

			// Loop through all Authorizations and check if the action is permitted by each
			for (Authorizer authorizer : authorizers) {
				AuthResponse response = authorizer.canUserPerformAction(authorizationCheck);
				if (response.getIsAuthSuccess().booleanValue() == false) {
					pzLogger.log("Failed authorization check.", Severity.INFORMATIONAL,
							new AuditElement(authorizationCheck.getUsername(), "authorizationCheckFailed", authorizationCheck.toString()));
					throw new AuthorizationException("Failed to Authorize", response);
				}
			}

			// Return successful response.
			pzLogger.log("Passed authorization check.", Severity.INFORMATIONAL,
					new AuditElement(authorizationCheck.getUsername(), "authorizationCheckPassed", authorizationCheck.toString()));
			return new ResponseEntity<AuthResponse>(
					new AuthResponse(true, mongoAccessor.getUserProfileByUsername(authorizationCheck.getUsername())), HttpStatus.OK);
		} catch (AuthorizationException authException) {
			String error = String.format("%s: %s", authException.getMessage(), authException.getResponse().getDetails().toString());
			LOGGER.error(error, authException);
			pzLogger.log(error, Severity.ERROR);
			// Return Error
			return new ResponseEntity<AuthResponse>(new AuthResponse(false, error), HttpStatus.UNAUTHORIZED);
		} catch (Exception exception) {
			// Logging
			String error = String.format("Error checking authorization: %s: %s",
					authorizationCheck != null ? authorizationCheck.toString() : "Null Payload Sent.", exception.getMessage());
			LOGGER.error(error, exception);
			pzLogger.log(error, Severity.ERROR);
			// Return Error
			return new ResponseEntity<AuthResponse>(new AuthResponse(false, error), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Generates a new API Key based on the provided username and credential for GeoAxis.
	 * 
	 * @param body
	 *            A JSON object containing the 'username' and 'credential' fields.
	 * 
	 * @return String UUID generated from the UUIDFactory in pz-jobcommon
	 */
	@RequestMapping(value = "/key", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<PiazzaResponse> generateApiKey() {
		try {
			String headerValue = request.getHeader("Authorization");
			String username = null;
			String uuid = null;

			if (headerValue != null) {
				String[] headerParts = headerValue.split(" ");

				if (headerParts.length == 2) {

					String decodedAuthNInfo = new String(Base64.getDecoder().decode(headerParts[1]), StandardCharsets.UTF_8);

					// PKI Auth
					if (decodedAuthNInfo.split(":").length == 1) {
						AuthResponse authResponse = piazzaAuthenticator.getAuthenticationDecision(decodedAuthNInfo.split(":")[0]);
						if (authResponse.getIsAuthSuccess()) {
							username = authResponse.getUserProfile().getUsername();
							uuid = uuidFactory.getUUID();
						}
					}

					// BASIC Auth
					else if (decodedAuthNInfo.split(":").length == 2) {
						String[] decodedUserPassParts = decodedAuthNInfo.split(":");
						username = decodedUserPassParts[0];
						String credential = decodedUserPassParts[1];
						AuthResponse authResponse = piazzaAuthenticator.getAuthenticationDecision(username, credential);

						if (authResponse.getIsAuthSuccess()) {
							uuid = uuidFactory.getUUID();
						}
					}

					if (uuid != null && username != null) {
						// Update the API Key in the UUID Collection
						if (mongoAccessor.getApiKey(username) != null) {
							mongoAccessor.updateApiKey(username, uuid);
						} else {
							mongoAccessor.createApiKey(username, uuid);
						}

						// Return the Key
						pzLogger.log("Successfully verified Key.", Severity.INFORMATIONAL,
								new AuditElement(username, "generateApiKey", ""));
						return new ResponseEntity<>(new UUIDResponse(uuid), HttpStatus.OK);
					}
				}
			}

			String error = "Authentication failed for user " + username;
			pzLogger.log(error, Severity.INFORMATIONAL, new AuditElement(username, "failedToGenerateKey", ""));
			return new ResponseEntity<>(new ErrorResponse(error, IDAM_COMPONENT_NAME), HttpStatus.UNAUTHORIZED);
		} catch (Exception exception) {
			String error = String.format("Error retrieving API Key: %s", exception.getMessage());
			LOGGER.error(error, exception);
			pzLogger.log(error, Severity.ERROR);
			return new ResponseEntity<>(new ErrorResponse(error, IDAM_COMPONENT_NAME), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Deletes API Key with provided UUID
	 * 
	 * @param key
	 * @return PiazzaResponse
	 */
	@RequestMapping(value = "/v2/key/{key}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<PiazzaResponse> deleteApiKey(@PathVariable(value = "key") String uuid) {
		try {
			//Delete API Key
			String username = mongoAccessor.getUsername(uuid);
			mongoAccessor.deleteApiKey(uuid);
			
			//Log the action
			String response = String.format("User: %s API Key was deleted", username);
			pzLogger.log(response, Severity.INFORMATIONAL, new AuditElement(username, "deleteApiKey", ""));
			LOGGER.info(response);
			return new ResponseEntity<>(new SuccessResponse(response, IDAM_COMPONENT_NAME), HttpStatus.OK);
		} catch (Exception exception) {
			String error = String.format("Error deleting API Key: %s", exception.getMessage());
			LOGGER.error(error, exception);
			pzLogger.log(error, Severity.ERROR);
			return new ResponseEntity<>(new ErrorResponse(error, IDAM_COMPONENT_NAME), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
	
	/**
	 * Generates a new API Key based on the provided username and credential for GeoAxis.
	 * 
	 * @param body
	 *            A JSON object containing the 'username' and 'credential' fields.
	 * 
	 * @return String UUID generated from the UUIDFactory in pz-jobcommon
	 */
	@RequestMapping(value = "/v2/key", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<PiazzaResponse> generateApiKeyV2() {
		return generateApiKey();
	}

	/**
	 * Gets an existing key for the user. Does not generate one if it does not exist.
	 * 
	 * @param body
	 *            A JSON object containing the 'username' and 'credential' fields.
	 * 
	 * @return String UUID generated from the UUIDFactory in pz-jobcommon
	 */
	@RequestMapping(value = "/v2/key", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<PiazzaResponse> getExistingApiKey() {
		try {
			// Decode credentials. We need to get the username of this account.
			String authHeader = request.getHeader("Authorization");
			String username = null;

			// Ensure the Authorization Header is present
			if (authHeader != null) {
				String[] headerParts = authHeader.split(" ");
				// Ensure Valid Auth
				if (headerParts.length == 2) {
					String decodedAuthNInfo = new String(Base64.getDecoder().decode(headerParts[1]), StandardCharsets.UTF_8);
					// PKI Auth - authenticate and get username
					if (decodedAuthNInfo.split(":").length == 1) {
						AuthResponse authResponse = piazzaAuthenticator.getAuthenticationDecision(decodedAuthNInfo.split(":")[0]);
						if (authResponse.getIsAuthSuccess()) {
							username = authResponse.getUserProfile().getUsername();
						}
					}
					// BASIC Auth - authenticate and get username
					else if (decodedAuthNInfo.split(":").length == 2) {
						String[] decodedUserPassParts = decodedAuthNInfo.split(":");
						if (piazzaAuthenticator.getAuthenticationDecision(decodedUserPassParts[0], decodedUserPassParts[1])
								.getIsAuthSuccess()) {
							username = decodedUserPassParts[0];
						}
					}
					// Username found and authenticated. Get the API Key.
					if (username != null) {
						String apiKey = mongoAccessor.getApiKey(username);
						pzLogger.log(String.format("Successfully retrieved API Key for user %s.", username), Severity.INFORMATIONAL,
								new AuditElement(username, "getExistingApiKey", ""));
						return new ResponseEntity<>(new UUIDResponse(apiKey), HttpStatus.OK);
					}
				}
			}
			// If the username was not found and authenticated from the auth header, then no API Key can be returned.
			// Return an error.
			String error = "Could not get existing API Key.";
			pzLogger.log(error, Severity.INFORMATIONAL, new AuditElement(username, "failedToGetExistingKey", ""));
			return new ResponseEntity<>(new ErrorResponse(error, IDAM_COMPONENT_NAME), HttpStatus.UNAUTHORIZED);
		} catch (Exception exception) {
			// Log
			String error = String.format("Error retrieving API Key: %s", exception.getMessage());
			LOGGER.error(error, exception);
			pzLogger.log(error, Severity.ERROR);
			// Return Error
			return new ResponseEntity<>(new ErrorResponse(error, IDAM_COMPONENT_NAME), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	@RequestMapping(value = "/user", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<PiazzaResponse> addUser(
			@RequestParam(value = "username") String username,
			@RequestParam(value = "dn") String dn) {
		if (mongoAccessor.hasUserProfile(username, dn)) {
			String response = String.format("User: %s already exists", username);
			return new ResponseEntity<>(new ErrorResponse(response, IDAM_COMPONENT_NAME), HttpStatus.BAD_REQUEST);
		} else {
			final DateTime time = new DateTime();
			UserProfile userProfile = new UserProfile();
			userProfile.setUsername(username);
			userProfile.setCredential(BCrypt.hashpw(username, BCrypt.gensalt(12)));
			userProfile.setAdminCode("");
			userProfile.setCountry("us");
			userProfile.setDistinguishedName(dn);
			userProfile.setDutyCode("");
			userProfile.setCreatedBy("system");
			userProfile.setCreatedOn(time);
			userProfile.setCreatedOnString(time.toString());
			userProfile.setLastUpdatedOn(time);
			userProfile.setLastUpdatedOnString(time.toString());
			userProfile.setNPE(false);
			mongoAccessor.insertUserProfile(userProfile);
		}
		String response = String.format("User: %s was created", username);
		return new ResponseEntity<>(new SuccessResponse(response, IDAM_COMPONENT_NAME), HttpStatus.OK);
	}

	@RequestMapping(value = "/updatePassword", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<PiazzaResponse> updatePassword(
			@RequestBody Map<String, String> body) {
		try {
			// Decode credentials. We need to get the username of this account.
			String authHeader = request.getHeader("Authorization");
			String username = null;

			// Ensure the Authorization Header is present
			if (authHeader != null) {
				String[] headerParts = authHeader.split(" ");
				// Ensure Valid Auth
				if (headerParts.length == 2) {
					String decodedAuthNInfo = new String(Base64.getDecoder().decode(headerParts[1]), StandardCharsets.UTF_8);
					// PKI Auth - authenticate and get username
					if (decodedAuthNInfo.split(":").length == 1) {
						AuthResponse authResponse = piazzaAuthenticator.getAuthenticationDecision(decodedAuthNInfo.split(":")[0]);
						if (authResponse.getIsAuthSuccess()) {
							username = authResponse.getUserProfile().getUsername();
						}
					}
					// BASIC Auth - authenticate and get username
					else if (decodedAuthNInfo.split(":").length == 2) {
						String[] decodedUserPassParts = decodedAuthNInfo.split(":");
						if (piazzaAuthenticator.getAuthenticationDecision(decodedUserPassParts[0], decodedUserPassParts[1])
								.getIsAuthSuccess()) {
							username = decodedUserPassParts[0];
						}
					}
					// Username found and authenticated. Update the creds.
					if (username != null) {
						UserProfile userProfile = mongoAccessor.getUserProfileByUsername(username);
						final String newPassword = body.get("newPassword");
						userProfile.setCredential(BCrypt.hashpw(newPassword, BCrypt.gensalt(12)));
						mongoAccessor.updateUserProfile(userProfile);
						pzLogger.log(String.format("Successfully updated password for user %s.", username), Severity.INFORMATIONAL,
								new AuditElement(username, "updateCredential", ""));
						return new ResponseEntity<>(new SuccessResponse("Password Updated", IDAM_COMPONENT_NAME), HttpStatus.OK);
					}
				}
			}
			// If the username was not found and authenticated from the auth header, then no action can be taken.
			// Return an error.
			String error = "Could not update the credential.";
			pzLogger.log(error, Severity.INFORMATIONAL, new AuditElement(username, "failedToUpdateCredential", ""));
			return new ResponseEntity<>(new ErrorResponse(error, IDAM_COMPONENT_NAME), HttpStatus.UNAUTHORIZED);
		} catch (Exception exception) {
			// Log
			String error = String.format("Error updating credential: %s", exception.getMessage());
			LOGGER.error(error, exception);
			pzLogger.log(error, Severity.ERROR);
			// Return Error
			return new ResponseEntity<>(new ErrorResponse(error, IDAM_COMPONENT_NAME), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Deletes user with provided username
	 *
	 * @param username
	 * @return PiazzaResponse
	 */
	@RequestMapping(value = "/user/{username}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<PiazzaResponse> deleteUser(@PathVariable(value = "username") String username) {
		try {
			//Delete API Key
			mongoAccessor.deleteUserProfile(username);

			//Log the action
			String response = String.format("User: %s was deleted", username);
			pzLogger.log(response, Severity.INFORMATIONAL, new AuditElement(username, "deleteUser", ""));
			LOGGER.info(response);
			return new ResponseEntity<>(new SuccessResponse(response, IDAM_COMPONENT_NAME), HttpStatus.OK);
		} catch (Exception exception) {
			String error = String.format("Error deleting user: %s", exception.getMessage());
			LOGGER.error(error, exception);
			pzLogger.log(error, Severity.ERROR);
			return new ResponseEntity<>(new ErrorResponse(error, IDAM_COMPONENT_NAME), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
}
