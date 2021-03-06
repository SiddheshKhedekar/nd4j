package org.nd4j.compression.impl;

import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.javacpp.Pointer;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.executioner.GridExecutioner;
import org.nd4j.linalg.compression.CompressedDataBuffer;
import org.nd4j.linalg.compression.NDArrayCompressor;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author raver119@gmail.com
 */
public abstract class AbstractCompressor implements NDArrayCompressor {
    protected static Logger logger = LoggerFactory.getLogger(AbstractCompressor.class);

    @Override
    public INDArray compress(INDArray array) {
        INDArray dup = array.dup(array.ordering());

        Nd4j.getExecutioner().commit();

        dup.setData(compress(dup.data()));
        dup.markAsCompressed(true);

        return dup;
    }

    /**
     * Inplace compression of INDArray
     *
     * @param array
     */
    @Override
    public void compressi(INDArray array) {
        // TODO: lift this restriction
        if (array.isView())
            throw new UnsupportedOperationException("Impossible to apply inplace compression on View");

        array.setData(compress(array.data()));
        array.markAsCompressed(true);
    }

    @Override
    public void decompressi(INDArray array) {
        if (!array.isCompressed())
            return;

        array.markAsCompressed(false);
        array.setData(decompress(array.data()));
    }

    @Override
    public INDArray decompress(INDArray array) {
        DataBuffer buffer = decompress(array.data());
        DataBuffer shapeInfo = array.shapeInfoDataBuffer();
        INDArray rest = Nd4j.createArrayFromShapeBuffer(buffer, shapeInfo);

        return rest;
    }

    public abstract DataBuffer decompress(DataBuffer buffer);

    public abstract DataBuffer compress(DataBuffer buffer);

    protected DataBuffer.TypeEx convertType(DataBuffer.Type type) {
        if (type == DataBuffer.Type.HALF) {
            return DataBuffer.TypeEx.FLOAT16;
        } else if (type == DataBuffer.Type.FLOAT) {
            return DataBuffer.TypeEx.FLOAT;
        } else if (type == DataBuffer.Type.DOUBLE) {
            return DataBuffer.TypeEx.DOUBLE;
        } else
            throw new IllegalStateException("Unknown dataType: [" + type + "]");
    }

    protected DataBuffer.TypeEx getGlobalTypeEx() {
        DataBuffer.Type type = Nd4j.dataType();

        return convertType(type);
    }

    protected DataBuffer.TypeEx getBufferTypeEx(DataBuffer buffer) {
        DataBuffer.Type type = buffer.dataType();

        return convertType(type);
    }

    /**
     * This method creates compressed INDArray from Java float array, skipping usual INDArray instantiation routines
     * Please note: This method compresses input data as vector
     *
     * @param data
     * @return
     */
    @Override
    public INDArray compress(float[] data) {
        return compress(data, new int[] {1, data.length}, Nd4j.order());
    }

    /**
     * This method creates compressed INDArray from Java double array, skipping usual INDArray instantiation routines
     * Please note: This method compresses input data as vector
     *
     * @param data
     * @return
     */
    @Override
    public INDArray compress(double[] data) {
        return compress(data, new int[] {1, data.length}, Nd4j.order());
    }

    /**
     * This method creates compressed INDArray from Java float array, skipping usual INDArray instantiation routines
     *
     * @param data
     * @param shape
     * @param order
     * @return
     */
    @Override
    public INDArray compress(float[] data, int[] shape, char order) {
        FloatPointer pointer = new FloatPointer(data);

        DataBuffer shapeInfo = Nd4j.getShapeInfoProvider().createShapeInformation(shape, order);
        DataBuffer buffer = compressPointer(DataBuffer.TypeEx.FLOAT, pointer, data.length, 4);

        return Nd4j.createArrayFromShapeBuffer(buffer, shapeInfo);
    }

    /**
     * This method creates compressed INDArray from Java double array, skipping usual INDArray instantiation routines
     *
     * @param data
     * @param shape
     * @param order
     * @return
     */
    @Override
    public INDArray compress(double[] data, int[] shape, char order) {
        DoublePointer pointer = new DoublePointer(data);

        DataBuffer shapeInfo = Nd4j.getShapeInfoProvider().createShapeInformation(shape, order);
        DataBuffer buffer = compressPointer(DataBuffer.TypeEx.DOUBLE, pointer, data.length, 8);

        return Nd4j.createArrayFromShapeBuffer(buffer, shapeInfo);
    }

    protected abstract CompressedDataBuffer compressPointer(DataBuffer.TypeEx srcType, Pointer srcPointer, int length,
                    int elementSize);
}
