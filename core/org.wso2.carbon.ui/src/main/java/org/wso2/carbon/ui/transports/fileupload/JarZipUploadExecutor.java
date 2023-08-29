/*
 * Copyright 2005,2006 WSO2, Inc. http://www.wso2.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.carbon.ui.transports.fileupload;

import org.wso2.carbon.CarbonException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Handles Jar, Zip file upload and creating a service archive out of it
 * This class is totally comply with add_new_jar_zip.xsl. Thus, should not use in general purpose
 * activities.
 */
public class JarZipUploadExecutor extends org.wso2.carbon.ui.transports.fileupload.AbstractFileUploadExecutor {

    public boolean execute(HttpServletRequest request, HttpServletResponse response)
            throws CarbonException, IOException {

        log.warn("JarZipUpload method is not supported.");
        return false;
    }
}