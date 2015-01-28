/*
 *  Copyright WSO2 Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.wso2.carbon.appmgt.gateway.handlers;

import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMDocument;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.soap.*;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.addressing.RelatesTo;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpHeaders;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.core.axis2.Axis2Sender;
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.wso2.carbon.appmgt.gateway.internal.ServiceReferenceHolder;
import org.wso2.carbon.appmgt.impl.AppMConstants;

import javax.xml.namespace.QName;
import java.util.*;

public class Utils {
    
    private static final Log log = LogFactory.getLog(Utils.class);
    
    public static void sendFault(MessageContext messageContext, int status) {
        org.apache.axis2.context.MessageContext axis2MC = ((Axis2MessageContext) messageContext).
                getAxis2MessageContext();

        axis2MC.setProperty(NhttpConstants.HTTP_SC, status);
        messageContext.setResponse(true);
        messageContext.setProperty("RESPONSE", "true");
        messageContext.setTo(null);        
        axis2MC.removeProperty("NO_ENTITY_BODY");
        String method = (String) axis2MC.getProperty(Constants.Configuration.HTTP_METHOD);
        if (method.matches("^(?!.*(POST|PUT)).*$")) {
            // If the request was not an entity enclosing request, send a XML response back
            axis2MC.setProperty(Constants.Configuration.MESSAGE_TYPE, "application/xml");
        }
        // Always remove the ContentType - Let the formatter do its thing
        axis2MC.removeProperty(Constants.Configuration.CONTENT_TYPE);
        Map headers = (Map) axis2MC.getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
        if (headers != null) {
            headers.remove(HttpHeaders.AUTHORIZATION);
            // headers.remove(HttpHeaders.ACCEPT);
            headers.remove(HttpHeaders.AUTHORIZATION);
            //headers.remove(HttpHeaders.ACCEPT);
            //Default we will send xml out put if error_message_type is json then we will send json response to client
            // We can set this parameter in _auth_failure_handler_ as follows
            /*<sequence name="_auth_failure_handler_">
            <property name="error_message_type" value="application/json"/>
            <sequence key="_build_"/>
            </sequence>     */
            if (messageContext.getProperty("error_message_type") != null &&
                    messageContext.getProperty("error_message_type").toString().equalsIgnoreCase("application/json")) {
                axis2MC.setProperty(Constants.Configuration.MESSAGE_TYPE, "application/json");
            }

            headers.remove(HttpHeaders.HOST);
        }
        Axis2Sender.sendBack(messageContext);
    }
    
    public static void setFaultPayload(MessageContext messageContext, OMElement payload) {
        OMElement firstChild = messageContext.getEnvelope().getBody().getFirstElement();
        if (firstChild != null) {
            firstChild.insertSiblingAfter(payload);
            firstChild.detach();
        } else {
            messageContext.getEnvelope().getBody().addChild(payload);
        }        
    }
    
    public static void setSOAPFault(MessageContext messageContext, String code, 
                                    String reason, String detail) {
        SOAPFactory factory = (messageContext.isSOAP11() ?
                OMAbstractFactory.getSOAP11Factory() : OMAbstractFactory.getSOAP12Factory());

        OMDocument soapFaultDocument = factory.createOMDocument();
        SOAPEnvelope faultEnvelope = factory.getDefaultFaultEnvelope();
        soapFaultDocument.addChild(faultEnvelope);

        SOAPFault fault = faultEnvelope.getBody().getFault();
        if (fault == null) {
            fault = factory.createSOAPFault();
        }

        SOAPFaultCode faultCode = factory.createSOAPFaultCode();
        if (messageContext.isSOAP11()) {
            faultCode.setText(new QName(fault.getNamespace().getNamespaceURI(), code));
        } else {
            SOAPFaultValue value = factory.createSOAPFaultValue(faultCode);
            value.setText(new QName(fault.getNamespace().getNamespaceURI(), code));            
        }
        fault.setCode(faultCode);

        SOAPFaultReason faultReason = factory.createSOAPFaultReason();
        if (messageContext.isSOAP11()) {
            faultReason.setText(reason);
        } else {
            SOAPFaultText text = factory.createSOAPFaultText();
            text.setText(reason);
            text.setLang("en");
            faultReason.addSOAPText(text);            
        }
        fault.setReason(faultReason);

        SOAPFaultDetail soapFaultDetail = factory.createSOAPFaultDetail();
        soapFaultDetail.setText(detail);
        fault.setDetail(soapFaultDetail);
        
        // set the all headers of original SOAP Envelope to the Fault Envelope
        if (messageContext.getEnvelope() != null) {
            SOAPHeader soapHeader = messageContext.getEnvelope().getHeader();
            if (soapHeader != null) {
                for (Iterator iterator = soapHeader.examineAllHeaderBlocks(); iterator.hasNext();) {
                    Object o = iterator.next();
                    if (o instanceof SOAPHeaderBlock) {
                        SOAPHeaderBlock header = (SOAPHeaderBlock) o;
                        faultEnvelope.getHeader().addChild(header);
                    } else if (o instanceof OMElement) {
                        faultEnvelope.getHeader().addChild((OMElement) o);
                    }
                }
            }
        }

        try {
            messageContext.setEnvelope(faultEnvelope);
        } catch (AxisFault af) {
            log.error("Error while setting SOAP fault as payload", af);
            return;
        }

        if (messageContext.getFaultTo() != null) {
            messageContext.setTo(messageContext.getFaultTo());
        } else if (messageContext.getReplyTo() != null) {
            messageContext.setTo(messageContext.getReplyTo());
        } else {
            messageContext.setTo(null);
        }

        // set original messageID as relatesTo
        if (messageContext.getMessageID() != null) {
            RelatesTo relatesTo = new RelatesTo(messageContext.getMessageID());
            messageContext.setRelatesTo(new RelatesTo[] { relatesTo });
        }
    }
    
    public static String getAllowedOrigin(String currentRequestOrigin) {
    	String allowedOrigins = ServiceReferenceHolder.getInstance().getAPIManagerConfiguration().
    	        getFirstProperty(AppMConstants.CORS_CONFIGURATION_ACCESS_CTL_ALLOW_ORIGIN);
    	if (allowedOrigins != null) {
    		String[] origins = allowedOrigins.split(",");
    		List<String> originsList = new LinkedList<String>();
    		for (String origin: origins) {
    			originsList.add(origin.trim());
    		}
    		
    		if (currentRequestOrigin != null && originsList.contains(currentRequestOrigin)) {
    			allowedOrigins = currentRequestOrigin;
    		} else {
    			allowedOrigins = null; 
    		}
    	}
    	
    	return allowedOrigins;
    }
    
    public static String getAllowedHeaders() {
    	return ServiceReferenceHolder.getInstance().getAPIManagerConfiguration().
    	        getFirstProperty(AppMConstants.CORS_CONFIGURATION_ACCESS_CTL_ALLOW_HEADERS);
    }
    
    public static String getAllowedMethods() {
    	return ServiceReferenceHolder.getInstance().getAPIManagerConfiguration().
    	        getFirstProperty(AppMConstants.CORS_CONFIGURATION_ACCESS_CTL_ALLOW_METHODS);
    }
    
    public static boolean isCORSEnabled() {
    	String corsEnabled = ServiceReferenceHolder.getInstance().getAPIManagerConfiguration().
    	        getFirstProperty(AppMConstants.CORS_CONFIGURATION_ENABLED);
    	    	    	
    	return Boolean.parseBoolean(corsEnabled);
    }

    public static String getAuthenticationCookie(MessageContext synCtx) {
        return (String) synCtx.getProperty(AppMConstants.APPM_SAML2_COOKIE);

    }

}
