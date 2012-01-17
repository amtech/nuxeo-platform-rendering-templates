package org.nuxeo.ecm.platform.template.processors.xdocreport;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.impl.blob.FileBlob;
import org.nuxeo.ecm.core.api.model.Property;
import org.nuxeo.ecm.core.schema.types.Type;
import org.nuxeo.ecm.core.schema.types.primitives.BooleanType;
import org.nuxeo.ecm.core.schema.types.primitives.DateType;
import org.nuxeo.ecm.core.schema.types.primitives.StringType;
import org.nuxeo.ecm.platform.rendering.fm.adapters.DocumentObjectWrapper;
import org.nuxeo.ecm.platform.template.InputType;
import org.nuxeo.ecm.platform.template.TemplateInput;
import org.nuxeo.ecm.platform.template.adapters.doc.TemplateBasedDocument;
import org.nuxeo.ecm.platform.template.adapters.source.TemplateSourceDocument;
import org.nuxeo.ecm.platform.template.fm.FreeMarkerVariableExtractor;
import org.nuxeo.ecm.platform.template.processors.AbstractTemplateProcessor;
import org.nuxeo.ecm.platform.template.processors.TemplateProcessor;
import org.nuxeo.ecm.platform.template.processors.fm.IncludeManager;
import org.nuxeo.runtime.api.Framework;

import fr.opensagres.xdocreport.document.IXDocReport;
import fr.opensagres.xdocreport.document.images.IImageProvider;
import fr.opensagres.xdocreport.document.registry.XDocReportRegistry;
import fr.opensagres.xdocreport.template.IContext;
import fr.opensagres.xdocreport.template.TemplateEngineKind;
import fr.opensagres.xdocreport.template.formatter.FieldsMetadata;

public class XDocReportProcessor extends AbstractTemplateProcessor implements
        TemplateProcessor {

    protected static final Log log = LogFactory.getLog(XDocReportProcessor.class);

    public static final String TEMPLATE_TYPE = "XDocReport";

    public static final String OOO_TEMPLATE_TYPE = "OpenDocument";

    public static final String DocX_TEMPLATE_TYPE = "DocX";

    protected String getTemplateFormat(Blob blob) {
        String filename = blob.getFilename();
        if (filename == null && blob instanceof FileBlob) {
            File file = ((FileBlob) blob).getFile();
            if (file != null) {
                filename = file.getName();
            }
        }
        if (filename != null && !filename.isEmpty()) {
            if (filename.endsWith(".docx")) {
                return DocX_TEMPLATE_TYPE;
            } else if (filename.endsWith(".odt")) {
                return OOO_TEMPLATE_TYPE;
            }
        }
        return OOO_TEMPLATE_TYPE;
    }

    protected String getTemplateFormat(TemplateBasedDocument templateDocument) {
        try {
            return getTemplateFormat(templateDocument.getTemplateBlob());
        } catch (Exception e) {
            log.error("Unable to read Blob to determine Template format", e);
            return OOO_TEMPLATE_TYPE;
        }
    }

    @Override
    public List<TemplateInput> getInitialParametersDefinition(Blob blob)
            throws Exception {

        List<TemplateInput> params = new ArrayList<TemplateInput>();
        String xmlContent = null;

        if (OOO_TEMPLATE_TYPE.equals(getTemplateFormat(blob))) {
            xmlContent = ZipXmlHelper.readXMLContent(blob,
                    ZipXmlHelper.OOO_MAIN_FILE);
        } else if (DocX_TEMPLATE_TYPE.equals(getTemplateFormat(blob))) {
            xmlContent = ZipXmlHelper.readXMLContent(blob,
                    ZipXmlHelper.DOCX_MAIN_FILE);
        }

        if (xmlContent != null) {
            List<String> vars = FreeMarkerVariableExtractor.extractVariables(xmlContent);

            for (String var : vars) {
                TemplateInput input = new TemplateInput(var);
                params.add(input);
            }

            // add includes
            params.addAll(IncludeManager.getIncludes(xmlContent));
        }
        return params;

    }

    @Override
    public Blob renderTemplate(TemplateBasedDocument templateBasedDocument)
            throws Exception {

        Blob sourceTemplateBlob = templateBasedDocument.getTemplateBlob();
        if (templateBasedDocument.getSourceTemplateDoc() != null) {
            sourceTemplateBlob = templateBasedDocument.getSourceTemplateDoc().getAdapter(
                    TemplateSourceDocument.class).getTemplateBlob();
        }

        // load the template
        IXDocReport report = XDocReportRegistry.getRegistry().loadReport(
                sourceTemplateBlob.getStream(), TemplateEngineKind.Freemarker);

        // manage parameters
        List<TemplateInput> params = templateBasedDocument.getParams();
        FieldsMetadata metadata = new FieldsMetadata();
        for (TemplateInput param : params) {
            if (param.getType() == InputType.PictureProperty) {
                metadata.addFieldAsImage(param.getName());
            }
        }
        report.setFieldsMetadata(metadata);

        // fill Freemarker context
        IContext context = report.createContext();

        DocumentObjectWrapper nuxeoWrapper = new DocumentObjectWrapper(null);

        for (TemplateInput param : params) {
            if (param.isSourceValue()) {
                Property property = null;
                try {
                    property = templateBasedDocument.getAdaptedDoc().getProperty(
                            param.getSource());
                } catch (Throwable e) {
                    log.warn("Unable to ready property " + param.getSource(), e);
                }
                if (property != null) {
                    Serializable value = property.getValue();
                    if (value != null) {
                        if (param.getType() == InputType.Include) {
                            // XXX TODO
                        } else {
                            if (Blob.class.isAssignableFrom(value.getClass())) {
                                Blob blob = (Blob) value;
                                if (param.getType() == InputType.PictureProperty) {
                                    if (blob.getMimeType() == null
                                            || "".equals(blob.getMimeType().trim())) {
                                        blob.setMimeType("image/jpeg");
                                    }
                                    IImageProvider imgBlob = new BlobImageProvider(
                                            blob);
                                    context.put(param.getName(), imgBlob);
                                    metadata.addFieldAsImage(param.getName());
                                }
                            } else {
                                context.put(param.getName(),
                                        nuxeoWrapper.wrap(property));
                            }
                        }
                    } else {
                        // no available value, try to find a default one ...
                        Type pType = property.getType();
                        if (pType.getName().equals(BooleanType.ID)) {
                            context.put(param.getName(), new Boolean(false));
                        } else if (pType.getName().equals(DateType.ID)) {
                            context.put(param.getName(), new Date());
                        } else if (pType.getName().equals(StringType.ID)) {
                            context.put(param.getName(), "");
                        } else if (pType.getName().equals(StringType.ID)) {
                            context.put(param.getName(), "");
                        } else {
                            context.put(param.getName(), new Object());
                        }
                    }
                }
            } else {
                if (InputType.StringValue.equals(param.getType())) {
                    context.put(param.getName(), param.getStringValue());
                } else if (InputType.BooleanValue.equals(param.getType())) {
                    context.put(param.getName(), param.getBooleanValue());
                } else if (InputType.DateValue.equals(param.getType())) {
                    context.put(param.getName(), param.getDateValue());
                }
            }
        }

        // add default context vars
        DocumentModel doc = templateBasedDocument.getAdaptedDoc();
        context.put("doc", nuxeoWrapper.wrap(doc));
        context.put("document", nuxeoWrapper.wrap(doc));
        // context.put("auditEntries", XXX );

        File workingDir = getWorkingDir();
        File generated = new File(workingDir, "XDOCReportresult-"
                + System.currentTimeMillis());
        generated.createNewFile();

        OutputStream out = new FileOutputStream(generated);

        report.process(context, out);

        Blob newBlob = new FileBlob(generated);

        // newBlob.setMimeType("application/vnd.oasis.opendocument.text");
        if (templateBasedDocument.getTemplateBlob() != null) {
            newBlob.setFilename(templateBasedDocument.getTemplateBlob().getFilename());
        } else {
            newBlob.setFilename(sourceTemplateBlob.getFilename());
        }
        // mark the file for automatic deletion on GC
        Framework.trackFile(generated, newBlob);
        return newBlob;
    }

}