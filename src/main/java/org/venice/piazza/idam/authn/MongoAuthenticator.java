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
package org.venice.piazza.idam.authn;

import model.logger.AuditElement;
import model.logger.Severity;
import model.response.AuthResponse;
import model.security.authz.UserProfile;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Component;
import org.venice.piazza.idam.data.MongoAccessor;
import util.PiazzaLogger;

import static org.apache.commons.codec.digest.DigestUtils.sha256;

/**
 * AuthN requests to MongoDB to verify user identity via user name/password.
 * 
 * @author John McMahon
 *
 */
@Component
@Profile({ "mongo" })
public class MongoAuthenticator implements PiazzaAuthenticator {

	@Autowired
	private PiazzaLogger logger;
	@Autowired
	private MongoAccessor mongoAccessor;

	@Override
	public AuthResponse getAuthenticationDecision(final String username, final String credential) {
		logger.log(String.format("Performing credential check for Username %s to Mongo.", username), Severity.INFORMATIONAL,
				new AuditElement(username, "loginAttempt", ""));
		final UserProfile userProfile = mongoAccessor.getUserProfileByUsername(username);
		boolean loginSuccess = false;
		if (BCrypt.checkpw(credential, userProfile.getCredential())) {
			// user has authenticated
			loginSuccess = true;
			logger.log(String.format("Mongo response has returned authentication %s", loginSuccess),
					Severity.INFORMATIONAL,
					new AuditElement("idam", "userLoggedIn", ""));

			return new AuthResponse(loginSuccess, userProfile, "");
		} else {
			// We have a problem
			logger.log(String.format("Mongo response has returned authentication %s", loginSuccess),
					Severity.INFORMATIONAL,
					new AuditElement("idam", "userFailedAuthentication", ""));

			return new AuthResponse(loginSuccess, null, "some problem occurred");
		}
	}

	@Override
	public AuthResponse getAuthenticationDecision(String pem) {
		// Not implemented
		return null;
	}
}