/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2009 The eXist Project
 *  http://exist-db.org
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 *
 *  $Id$
 */
package org.exist.xquery.functions.validation;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.xerces.xni.parser.XMLEntityResolver;

import org.exist.Namespaces;
import org.exist.dom.QName;
import org.exist.memtree.MemTreeBuilder;
import org.exist.memtree.NodeImpl;
import org.exist.storage.BrokerPool;
import org.exist.storage.io.ExistIOException;
import org.exist.util.Configuration;
import org.exist.util.XMLReaderObjectFactory;
import org.exist.validation.GrammarPool;
import org.exist.validation.ValidationContentHandler;
import org.exist.validation.ValidationReport;
import org.exist.validation.resolver.SearchResourceResolver;
import org.exist.validation.resolver.eXistXMLCatalogResolver;
import org.exist.xquery.BasicFunction;
import org.exist.xquery.Cardinality;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.value.BooleanValue;
import org.exist.xquery.value.FunctionParameterSequenceType;
import org.exist.xquery.value.FunctionReturnSequenceType;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceType;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.ValueSequence;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 *   xQuery function for validation of XML instance documents
 * using grammars like XSDs and DTDs.
 *
 * @author Dannes Wessels (dizzzz@exist-db.org)
 */
public class Parse extends BasicFunction {

    private static final String extendedFunctionTxt =
            "Validate document by parsing $instance. Optionally " +
            "grammar caching can be uses and " +
            "an XML catalog can be specified.";

    private static final String documentTxt
            = "Document referenced as xs:anyURI() or a node (element or fn:doc()).";
    private static final String catalogTxt
            = "Catalog referenced as xs:anyURI(), doc() or collection().";
    private static final String cacheTxt
            = "Set true() to use grammar cache.";

    private final BrokerPool brokerPool;

    // Setup function signature
    public final static FunctionSignature signatures[] = {
        new FunctionSignature(
                new QName("parse", ValidationModule.NAMESPACE_URI, ValidationModule.PREFIX),
                extendedFunctionTxt,
                new SequenceType[]{
                    new FunctionParameterSequenceType("instance", Type.ITEM, Cardinality.EXACTLY_ONE,
                    documentTxt),
                    new FunctionParameterSequenceType("enable-grammar-cache", Type.BOOLEAN, Cardinality.EXACTLY_ONE,
                    cacheTxt)
                },
                new FunctionReturnSequenceType(Type.BOOLEAN, Cardinality.EXACTLY_ONE,
                Shared.simplereportText)),
                
        new FunctionSignature(
                new QName("parse", ValidationModule.NAMESPACE_URI, ValidationModule.PREFIX),
                extendedFunctionTxt,
                new SequenceType[]{
                    new FunctionParameterSequenceType("instance", Type.ITEM, Cardinality.EXACTLY_ONE,
                    documentTxt),
                    new FunctionParameterSequenceType("enable-grammar-cache", Type.BOOLEAN, Cardinality.EXACTLY_ONE,
                    cacheTxt),
                    new FunctionParameterSequenceType("catalog", Type.ITEM, Cardinality.ZERO_OR_MORE,
                    catalogTxt),
                },
                new FunctionReturnSequenceType(Type.BOOLEAN, Cardinality.EXACTLY_ONE,
                Shared.simplereportText)),

        new FunctionSignature(
                new QName("parse-report", ValidationModule.NAMESPACE_URI, ValidationModule.PREFIX),
                extendedFunctionTxt + " An xml report is returned.",
                new SequenceType[]{
                    new FunctionParameterSequenceType("instance", Type.ITEM, Cardinality.EXACTLY_ONE,
                    documentTxt),
                    new FunctionParameterSequenceType("enable-grammar-cache", Type.BOOLEAN, Cardinality.EXACTLY_ONE,
                    cacheTxt),
                },
                new FunctionReturnSequenceType(Type.NODE, Cardinality.EXACTLY_ONE,
                Shared.xmlreportText)),

        new FunctionSignature(
                new QName("parse-report", ValidationModule.NAMESPACE_URI, ValidationModule.PREFIX),
                extendedFunctionTxt + " An xml report is returned.",
                new SequenceType[]{
                    new FunctionParameterSequenceType("instance", Type.ITEM, Cardinality.EXACTLY_ONE,
                    documentTxt),
                    new FunctionParameterSequenceType("enable-grammar-cache", Type.BOOLEAN, Cardinality.EXACTLY_ONE,
                    cacheTxt),
                    new FunctionParameterSequenceType("catalog", Type.ITEM, Cardinality.ZERO_OR_MORE,
                    catalogTxt),
                },
                new FunctionReturnSequenceType(Type.NODE, Cardinality.EXACTLY_ONE,
                Shared.xmlreportText))
    };

    public Parse(XQueryContext context, FunctionSignature signature) {
        super(context, signature);
        brokerPool = context.getBroker().getBrokerPool();
    }

    /**
     * @throws org.exist.xquery.XPathException 
     * @see BasicFunction#eval(Sequence[], Sequence)
     */
    public Sequence eval(Sequence[] args, Sequence contextSequence)
            throws XPathException {

        XMLEntityResolver entityResolver = null;
        GrammarPool grammarPool = null;
        File tmpFile = null;

        ValidationReport report = new ValidationReport();
        ValidationContentHandler contenthandler = new ValidationContentHandler();

        try {
            report.start();

            // Get initialized parser
            XMLReader xmlReader = getXMLReader();

            // Setup validation reporting
            xmlReader.setContentHandler(contenthandler);
            xmlReader.setErrorHandler(report);

            // Get inputstream for instance document
            InputSource instance = Shared.getInputSource(args[0].itemAt(0), context);

            // Handle catalog
            if(args.length==2){
                LOG.debug("No Catalog specified");

            } else if (args[2].isEmpty()) {
                // Use system catalog
                Configuration config = brokerPool.getConfiguration();
                entityResolver = (eXistXMLCatalogResolver) config.getProperty(XMLReaderObjectFactory.CATALOG_RESOLVER);
                
            } else {
                // Get URL for catalog
                String catalogUrls[] = Shared.getUrls(args[2]);
                String singleUrl = catalogUrls[0];

                if (singleUrl.endsWith("/")) {
                    // Search grammar in collection specified by URL. Just one collection is used.
                    LOG.debug("Search for grammar in "+singleUrl);
                    entityResolver = new SearchResourceResolver(catalogUrls[0], brokerPool);
                    xmlReader.setProperty(XMLReaderObjectFactory.APACHE_PROPERTIES_ENTITYRESOLVER, entityResolver);

                } else if (singleUrl.endsWith(".xml")) {
                    LOG.debug("Using catalogs "+getStrings(catalogUrls));
                    entityResolver = new eXistXMLCatalogResolver();
                    ((eXistXMLCatalogResolver) entityResolver).setCatalogList(catalogUrls);
                    xmlReader.setProperty(XMLReaderObjectFactory.APACHE_PROPERTIES_ENTITYRESOLVER, entityResolver);

                } else {
                    LOG.error("Catalog URLs should end on / or .xml");
                }

            }

            boolean useCache = ((BooleanValue) args[1].itemAt(0)).getValue();
            // Use grammarpool
            if(useCache){
                LOG.debug("Grammar caching enabled.");
                Configuration config = brokerPool.getConfiguration();
                grammarPool = (GrammarPool) config.getProperty(XMLReaderObjectFactory.GRAMMER_POOL);
                xmlReader.setProperty(XMLReaderObjectFactory.APACHE_PROPERTIES_INTERNAL_GRAMMARPOOL, grammarPool);
            }

            // Parse document
            LOG.debug("Start parsing document");
            xmlReader.parse(instance);
            LOG.debug("Stopped parsing document");

            // Distill namespace from document
            report.setNamespaceUri(contenthandler.getNamespaceUri());


        } catch (MalformedURLException ex) {
            LOG.error(ex.getMessage());
            report.setException(ex);

        } catch (ExistIOException ex) {
            LOG.error(ex.getCause());
            report.setException(ex);

        } catch (Throwable ex) {
            LOG.error(ex);
            report.setException(ex);

        } finally {
            report.stop();

            if (tmpFile != null) {
                LOG.debug("Remove tmp file " + tmpFile.getAbsolutePath());
                tmpFile.delete();
            }
        }

        // Create response
        if (isCalledAs("parse")) {
            Sequence result = new ValueSequence();
            result.add(new BooleanValue(report.isValid()));
            return result;

        } else /* isCalledAs("parse-report") */ {
            MemTreeBuilder builder = context.getDocumentBuilder();
            NodeImpl result = Shared.writeReport(report, builder);
            return result;
        }
    }

    // ####################################

    
    private XMLReader getXMLReader() throws ParserConfigurationException, SAXException {

        // setup sax factory ; be sure just one instance!
        SAXParserFactory saxFactory = SAXParserFactory.newInstance();

        // Enable validation stuff
        saxFactory.setValidating(true);
        saxFactory.setNamespaceAware(true);

        // Create xml reader
        SAXParser saxParser = saxFactory.newSAXParser();
        XMLReader xmlReader = saxParser.getXMLReader();

        xmlReader.setFeature(Namespaces.SAX_VALIDATION, true);
        xmlReader.setFeature(Namespaces.SAX_VALIDATION_DYNAMIC, false);
        xmlReader.setFeature(XMLReaderObjectFactory.APACHE_FEATURES_VALIDATION_SCHEMA, true);
        xmlReader.setFeature(XMLReaderObjectFactory.APACHE_PROPERTIES_LOAD_EXT_DTD, true);
        xmlReader.setFeature(Namespaces.SAX_NAMESPACES_PREFIXES, true);

        return xmlReader;
    }

    // No-go ...processor is in validating mode
    private File preparseDTD(StreamSource instance, String systemId) throws IOException, TransformerConfigurationException, TransformerException {

        // prepare output tmp storage
        File tmp = File.createTempFile("DTDvalidation", "tmp");
        tmp.deleteOnExit();

        StreamResult result = new StreamResult(tmp);

        TransformerFactory tf = TransformerFactory.newInstance();

        Transformer transformer = tf.newTransformer();

        transformer.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, systemId);
        transformer.transform(instance, result);

        return tmp;
    }

    private static String getStrings(String[] data){
        StringBuilder sb = new StringBuilder();
        for(String field: data){
            sb.append(field);
            sb.append(" ");
        }
        return sb.toString();
    }

    /*
     *           // Prepare grammar ; does not work
            /*
            if (args[1].hasOne()) {
                // Get URL for grammar
                grammarUrl = Shared.getUrl(args[1].itemAt(0));

                // Special case for DTD, the document needs to be rewritten.
                if (grammarUrl.endsWith(".dtd")) {
                    StreamSource newInstance = Shared.getStreamSource(instance);
                    tmpFile = preparseDTD(newInstance, grammarUrl);
                    instance = new InputSource(new FileInputStream(tmpFile));

                } else if (grammarUrl.endsWith(".xsd")) {
                    xmlReader.setProperty(XMLReaderObjectFactory.APACHE_PROPERTIES_NONAMESPACESCHEMALOCATION, grammarUrl);

                } else {
                    throw new XPathException("Grammar type not supported.");
                }
            }
            */
}
