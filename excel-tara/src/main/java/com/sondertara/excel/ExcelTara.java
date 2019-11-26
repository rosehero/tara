package com.sondertara.excel;


import com.sondertara.common.util.StringUtil;
import com.sondertara.excel.common.Constant;
import com.sondertara.excel.entity.ExcelEntity;
import com.sondertara.excel.entity.ExcelHelper;
import com.sondertara.excel.exception.ExcelBootException;
import com.sondertara.excel.factory.ExcelMappingFactory;
import com.sondertara.excel.function.ExportFunction;
import com.sondertara.excel.function.ImportFunction;
import com.sondertara.excel.parser.ExcelReader;
import com.sondertara.excel.parser.ExcelWriter;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import com.sondertara.excel.task.ExcelGenerateTask;
import com.sondertara.excel.task.ExcelRunnable;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;


/**
 * excel构造器
 *
 * @author huangxiaohu
 */
@Slf4j
public class ExcelTara {


    private ThreadPoolExecutor taskPool;
    private HttpServletResponse httpServletResponse;
    private OutputStream outputStream;
    private InputStream inputStream;
    private Class excelClass;

    private ExcelHelper helper;
    private Integer rowAccessWindowSize;
    private Integer recordCountPerSheet;
    private Boolean openAutoColumWidth;


    /**
     * 导入构造器
     */
    protected ExcelTara(InputStream inputStream, Class excelClass) {
        this(null, null, inputStream, null, excelClass, null, null, null);
    }

    /**
     * OutputStream导出构造器,一般用于导出到ftp服务器
     */
    protected ExcelTara(OutputStream outputStream, ExcelHelper helper, Class excelClass) {
        this(null, outputStream, null, helper, excelClass,
                Constant.DEFAULT_ROW_ACCESS_WINDOW_SIZE, Constant.DEFAULT_RECORD_COUNT_PEER_SHEET,
                Constant.OPEN_AUTO_COLUMN_WIDTH);
    }

    /**
     * HttpServletResponse导出构造器,一般用于浏览器导出
     */
    protected ExcelTara(HttpServletResponse response, ExcelHelper helper, Class excelClass) {
        this(response, null, null, helper, excelClass,
                Constant.DEFAULT_ROW_ACCESS_WINDOW_SIZE, Constant.DEFAULT_RECORD_COUNT_PEER_SHEET,
                Constant.OPEN_AUTO_COLUMN_WIDTH);
    }

    protected ExcelTara(ExcelHelper helper, Class clazz) {
        this(null, null, null, helper, clazz, null, null, null);
    }

    /**
     * 构造器
     */
    protected ExcelTara(HttpServletResponse response, OutputStream outputStream,
                        InputStream inputStream
            , ExcelHelper helper, Class excelClass, Integer rowAccessWindowSize,
                        Integer recordCountPerSheet, Boolean openAutoColumWidth) {
        this.httpServletResponse = response;
        this.outputStream = outputStream;
        this.inputStream = inputStream;

        this.excelClass = excelClass;

        this.helper = helper;
        this.rowAccessWindowSize = rowAccessWindowSize;
        this.recordCountPerSheet = recordCountPerSheet;
        this.openAutoColumWidth = openAutoColumWidth;

        this.taskPool = new ThreadPoolExecutor(8, 16, 60,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 通过HttpServletResponse,一般用于在浏览器中导出excel
     */
    public static ExcelTara builder(HttpServletResponse httpServletResponse, ExcelHelper helper,
                                    Class clazz) {
        return new ExcelTara(httpServletResponse, helper, clazz);
    }

    /**
     * 通过OutputStream生成excel文件,一般用于异步导出大Excel文件到ftp服务器或本地路径
     */
    public static ExcelTara builder(OutputStream outputStream, ExcelHelper helper, Class clazz) {
        return new ExcelTara(outputStream, helper, clazz);
    }

    /**
     * 用于异步导出大Excel文件到ftp服务器或本地路径,格式为csv
     */
    public static ExcelTara builder(ExcelHelper helper, Class clazz) {
        return new ExcelTara(helper, clazz);
    }

    /**
     * HttpServletResponse 通用导出Excel构造器
     */
    public static ExcelTara builder(HttpServletResponse response, ExcelHelper helper,
                                    Class excelClass, Integer rowAccessWindowSize, Integer recordCountPerSheet,
                                    Boolean openAutoColumWidth) {
        return new ExcelTara(response, null, null
                , helper, excelClass, rowAccessWindowSize, recordCountPerSheet,
                openAutoColumWidth);
    }

    /**
     * OutputStream 通用导出Excel构造器
     */
    public static ExcelTara builder(OutputStream outputStream, ExcelHelper helper,
                                    Class excelClass, Integer rowAccessWindowSize, Integer recordCountPerSheet, Boolean openAutoColumWidth) {
        return new ExcelTara(null, outputStream, null
                , helper, excelClass, rowAccessWindowSize, recordCountPerSheet,
                openAutoColumWidth);
    }

    /**
     * 导入Excel文件数据
     */
    public static ExcelTara builder(InputStream inputStream, Class clazz) {
        return new ExcelTara(inputStream, clazz);
    }

    /**
     * 用于浏览器导出
     */
    public <R, T> void exportResponse(R param, ExportFunction<R, T> exportFunction) {
        SXSSFWorkbook sxssfWorkbook = null;
        try {
            try {
                verifyResponse();
                sxssfWorkbook = commonSingleSheet(param, exportFunction);
                download(sxssfWorkbook, httpServletResponse,
                        URLEncoder.encode(helper.getFileName() + ".xlsx", "UTF-8"));
            } finally {
                if (sxssfWorkbook != null) {
                    sxssfWorkbook.close();
                }
                if (httpServletResponse != null && httpServletResponse.getOutputStream() != null) {
                    httpServletResponse.getOutputStream().close();
                }
            }
        } catch (Exception e) {
            throw new ExcelBootException(e);
        }
    }

    /**
     * 通过OutputStream导出excel文件,一般用于异步导出大Excel文件到本地路径
     */
    public <R, T> void exportStream(R param, ExportFunction<R, T> exportFunction) {
        OutputStream outputStream = null;
        try {
            try {
                outputStream = generateStream(param, exportFunction);
                write(outputStream);
            } finally {
                if (outputStream != null) {
                    outputStream.close();
                }
            }
        } catch (Exception e) {
            throw new ExcelBootException(e);
        }
    }

    /**
     * 导出csv,一般用于异步导出大Excel文件到本地路径
     */
    public <R, T> String exportCsv(R param, ExportFunction<R, T> exportFunction) {
        log.info("开始导出csv");
        try {
            verifyAndBuildParams();
            ExcelEntity excelMapping = ExcelMappingFactory.loadExportExcelClass(excelClass, helper.getFileName());
            final String workPath = Constant.FILE_PATH + helper.getReceiptUser() + File.separator + helper.getFileName() + File.separator;
            helper.setWorkspace(workPath);
            ExcelRunnable excelRunnable = new ExcelGenerateTask<R, T>(param, exportFunction, excelMapping, helper);
            for (int i = 0; i < Constant.PRODUCER_COUNT; i++) {

                taskPool.submit((excelRunnable.newRunnableProducer()));
            }

            for (int i = 0; i < Constant.CONSUMER_COUNT; i++) {
                taskPool.submit(excelRunnable.newRunnableConsumer());
            }

            taskPool.shutdown();
            while (true) {
                if (taskPool.isTerminated()) {
                    log.info("文件处理结束");
                    //合并文件
                    ExcelWriter excelWriter = new ExcelWriter(excelMapping, workPath);
                    excelWriter.generateCsv();
                    log.info("csv生成完毕");
                    break;
                }
            }
            //返回文件path
            return workPath + helper.getFileName() + ".csv";
        } catch (Exception e) {
            throw new ExcelBootException(e);
        }

    }

    /**
     * 通过OutputStream导出excel文件,一般用于异步导出大Excel文件到ftp服务器
     */
    public <R, T> OutputStream generateStream(R param, ExportFunction<R, T> exportFunction)
            throws IOException {
        SXSSFWorkbook sxssfWorkbook = null;
        try {
            verifyStream();
            sxssfWorkbook = commonSingleSheet(param, exportFunction);
            sxssfWorkbook.write(outputStream);
            return outputStream;
        } catch (Exception e) {
            log.error("生成Excel发生异常! 异常信息:", e);
            if (sxssfWorkbook != null) {
                sxssfWorkbook.close();
            }
            throw new ExcelBootException(e);
        }
    }

    /**
     * 用于浏览器分sheet导出
     */
    public <R, T> void exportMultiSheetResponse(R param, ExportFunction<R, T> exportFunction) {
        SXSSFWorkbook sxssfWorkbook = null;
        try {
            try {
                verifyResponse();
                sxssfWorkbook = commonMultiSheet(param, exportFunction);
                download(sxssfWorkbook, httpServletResponse,
                        URLEncoder.encode(helper.getFileName() + ".xlsx", "UTF-8"));
            } finally {
                if (sxssfWorkbook != null) {
                    sxssfWorkbook.close();
                }
            }
        } catch (Exception e) {
            throw new ExcelBootException(e);
        }
    }

    /**
     * 通过OutputStream分sheet导出excel文件,一般用于异步导出大Excel文件到本地路径
     */
    public <R, T> void exportMultiSheetStream(R param, ExportFunction<R, T> exportFunction) {
        OutputStream outputStream = null;
        try {
            try {
                outputStream = generateMultiSheetStream(param, exportFunction);
                write(outputStream);
            } finally {
                if (outputStream != null) {
                    outputStream.close();
                }
            }
        } catch (Exception e) {
            throw new ExcelBootException(e);
        }
    }

    /**
     * 通过OutputStream分sheet导出excel文件,一般用于异步导出大Excel文件到ftp服务器
     */
    public <R, T> OutputStream generateMultiSheetStream(R param,
                                                        ExportFunction<R, T> exportFunction) throws IOException {
        SXSSFWorkbook sxssfWorkbook = null;
        try {
            verifyStream();
            sxssfWorkbook = commonMultiSheet(param, exportFunction);
            sxssfWorkbook.write(outputStream);
            return outputStream;
        } catch (Exception e) {
            log.error("分Sheet生成Excel发生异常! 异常信息:", e);
            if (sxssfWorkbook != null) {
                sxssfWorkbook.close();
            }
            throw new ExcelBootException(e);
        }
    }

    /**
     * 导出-导入模板
     */
    public void exportTemplate() {
        SXSSFWorkbook sxssfWorkbook = null;
        try {
            try {
                verifyResponse();
                verifyAndBuildParams();
                ExcelEntity excelMapping = ExcelMappingFactory
                        .loadExportExcelClass(excelClass, helper.getFileName());
                ExcelWriter excelWriter = new ExcelWriter(excelMapping, helper.getPageSize(),
                        rowAccessWindowSize, recordCountPerSheet);
                sxssfWorkbook = excelWriter.generateTemplateWorkbook();
                download(sxssfWorkbook, httpServletResponse,
                        URLEncoder.encode(helper.getFileName() + ".xlsx", "UTF-8"));
            } finally {
                if (sxssfWorkbook != null) {
                    sxssfWorkbook.close();
                }
                if (httpServletResponse != null && httpServletResponse.getOutputStream() != null) {
                    httpServletResponse.getOutputStream().close();
                }
            }
        } catch (Exception e) {
            throw new ExcelBootException(e);
        }
    }

    /**
     * 导入excel全部sheet
     */
    public void importExcel(Boolean enableIndex, ImportFunction importFunction) {
        try {
            if (importFunction == null) {
                throw new ExcelBootException("excelReadHandler参数为空!");
            }
            if (inputStream == null) {
                throw new ExcelBootException("inputStream参数为空!");
            }

            ExcelEntity excelMapping = ExcelMappingFactory.loadImportExcelClass(excelClass);
            ExcelReader excelReader = new ExcelReader(excelClass, excelMapping, importFunction, enableIndex);
            excelReader.process(inputStream);
        } catch (Exception e) {
            throw new ExcelBootException(e);
        }

    }

    private <R, T> SXSSFWorkbook commonSingleSheet(R param, ExportFunction<R, T> exportFunction)
            throws Exception {
        verifyAndBuildParams();
        ExcelEntity excelMapping = ExcelMappingFactory.loadExportExcelClass(excelClass, helper.getFileName());
        ExcelWriter excelWriter = new ExcelWriter(excelMapping, helper.getPageSize(), rowAccessWindowSize,
                recordCountPerSheet);
        return excelWriter.generateWorkbook(param, exportFunction);
    }

    private <R, T> SXSSFWorkbook commonMultiSheet(R param, ExportFunction<R, T> exportFunction)
            throws Exception {
        verifyAndBuildParams();
        ExcelEntity excelMapping = ExcelMappingFactory.loadExportExcelClass(excelClass, helper.getFileName());
        ExcelWriter excelWriter = new ExcelWriter(excelMapping, helper.getPageSize(), rowAccessWindowSize,
                recordCountPerSheet);
        return excelWriter.generateMultiSheetWorkbook(param, exportFunction);
    }

    /**
     * 生成文件
     */
    private void write(OutputStream out) throws IOException {
        if (null != out) {
            out.flush();
        }
    }

    /**
     * 构建Excel服务器响应格式
     */
    private void download(SXSSFWorkbook wb, HttpServletResponse response, String filename)
            throws IOException {
        OutputStream out = response.getOutputStream();
        response
                .setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-disposition",
                String.format("attachment; filename=%s", filename));
        if (null != out) {
            wb.write(out);
            out.flush();
        }
    }

    private void verifyResponse() {
        if (httpServletResponse == null) {
            throw new ExcelBootException("httpServletResponse参数为空!");
        }
    }

    private void verifyStream() {
        if (outputStream == null) {
            throw new ExcelBootException("outputStream参数为空!");
        }
    }

    private void verifyAndBuildParams() {
        if (excelClass == null) {
            throw new ExcelBootException("excelClass参数为空!");
        }
        if (helper == null) {
            throw new ExcelBootException("helper参数为空!");
        }

        if (StringUtil.isEmpty(helper.getFileName())) {
            throw new ExcelBootException("fileName参数为空!");
        }
        if (StringUtil.isEmpty(helper.getReceiptUser())) {
            helper.setReceiptUser("default_export");
        }
        if (helper.getPageSize() == null) {
            helper.setPageSize(Constant.DEFAULT_PAGE_SIZE);
        }
        if (helper.getPageStart() == null) {
            helper.setPageStart(1);
        }
        if (helper.getPageEnd() == null) {
            helper.setPageEnd(Integer.MAX_VALUE);
        }
    }

}