package org.nd4j.linalg.cpu.nativecpu.ops;


import lombok.Data;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.util.Pair;
import org.bytedeco.javacpp.*;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.complex.IComplexNDArray;
import org.nd4j.linalg.api.environment.Nd4jEnvironment;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.*;
import org.nd4j.linalg.api.ops.aggregates.Aggregate;
import org.nd4j.linalg.api.ops.aggregates.Batch;
import org.nd4j.linalg.api.ops.executioner.DefaultOpExecutioner;
import org.nd4j.linalg.api.ops.impl.accum.Variance;
import org.nd4j.linalg.api.rng.Random;
import org.nd4j.linalg.api.shape.Shape;
import org.nd4j.linalg.cache.ConstantHandler;
import org.nd4j.linalg.cache.TADManager;
import org.nd4j.linalg.cpu.nativecpu.CpuTADManager;
import org.nd4j.linalg.exception.ND4JIllegalStateException;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.util.ArrayUtil;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.nd4j.nativeblas.Nd4jBlas;

import java.util.*;


/**
 *
 * Native operation
 * executioner in c++
 *
 * @author Adam Gibson
 */
@Slf4j
public class NativeOpExecutioner extends DefaultOpExecutioner {
    private NativeOps loop = NativeOpsHolder.getInstance().getDeviceNativeOps();
    private ConstantHandler constantHandler = Nd4j.getConstantHandler();
    @Getter
    private CpuTADManager tadManager = new CpuTADManager();

    private static final String DEBUG_ENABLED = "ND4J_DEBUG";
    private static final String VERBOSE = "ND4J_VERBOSE";

    protected ThreadLocal<PointerPointer> extraz = new ThreadLocal<>();

    /**
     * Instead of allocating new memory chunks for each batch invocation, we reuse them on thread/opNum basis
     * Since for NativeOpExecutioner all executions are synchronous
     */
    private ThreadLocal<Map<Integer, Pointer>> batchPointers = new ThreadLocal<>();
    private ThreadLocal<Map<Integer, AggregateMemoryBlock>> memoryBlocks = new ThreadLocal<>();

    public NativeOpExecutioner() {
        tadManager.init(loop, constantHandler);

        Map<String, String> env = System.getenv();

        if (env.containsKey(DEBUG_ENABLED)) {
            try {
                boolean var = Boolean.parseBoolean(env.get(DEBUG_ENABLED));
                loop.enableDebugMode(var);
            } catch (Exception e) {
                log.error("Can't parse {}: [{}]", DEBUG_ENABLED, env.get(DEBUG_ENABLED));
            }
        }

        if (env.containsKey(VERBOSE)) {
            try {
                boolean var = Boolean.parseBoolean(env.get(VERBOSE));
                loop.enableVerboseMode(var);
            } catch (Exception e) {
                log.error("Can't parse {}: [{}]", VERBOSE, env.get(VERBOSE));
            }
        }
    }

    @Override
    public Op exec(Op op) {
        checkForCompression(op);

        if (op instanceof ScalarOp) {
            ScalarOp s = (ScalarOp) op;
            exec(s);
        } else if (op instanceof TransformOp) {
            TransformOp t = (TransformOp) op;
            exec(t);
        } else if (op instanceof Accumulation) {
            Accumulation ac = (Accumulation) op;
            exec(ac);
        } else if (op instanceof IndexAccumulation) {
            IndexAccumulation iac = (IndexAccumulation) op;
            exec(iac); //Currently using DefaultOpExecutioner
        } else if (op instanceof BroadcastOp) {
            BroadcastOp broadcastOp = (BroadcastOp) op;
            exec(broadcastOp, broadcastOp.getDimension());
        }

        return op;
    }


    @Override
    public INDArray exec(IndexAccumulation op, int... dimension) {
        if (dimension == null || dimension.length == 0)
            dimension = new int[] {Integer.MAX_VALUE};

        checkForCompression(op);

        validateDataType(Nd4j.dataType(), op);

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

        Arrays.sort(dimension);
        for (int i = 0; i < dimension.length; i++) {
            if (dimension[i] < 0)
                dimension[i] += op.x().rank();
        }
        //do op along all dimensions
        if (dimension.length == op.x().rank())
            dimension = new int[] {Integer.MAX_VALUE};



        int[] retShape = Shape.wholeArrayDimension(dimension) ? new int[] {1, 1}
                        : ArrayUtil.removeIndex(op.x().shape(), dimension);

        // This is obviously wrong for IndexReduce, op.x has no real value as return
        // if(op.x().isVector() && op.x().length() == ArrayUtil.prod(retShape))
        //     return op.x();


        //ensure vector is proper shape
        if (retShape.length == 1) {
            if (dimension[0] == 0)
                retShape = new int[] {1, retShape[0]};
            else
                retShape = new int[] {retShape[0], 1};
        } else if (retShape.length == 0) {
            retShape = new int[] {1, 1};
        }

        INDArray ret;
        if (op.x().data().dataType() == DataBuffer.Type.DOUBLE)
            ret = Nd4j.valueArrayOf(retShape, op.zeroDouble());
        else
            ret = Nd4j.valueArrayOf(retShape, op.zeroFloat());

        op.setZ(ret);
        //do op along all dimensions
        if (dimension.length == op.x().rank())
            dimension = new int[] {Integer.MAX_VALUE};


        Pointer dimensionAddress = constantHandler.getConstantBuffer(dimension).addressPointer();

        Pair<DataBuffer, DataBuffer> tadBuffers = tadManager.getTADOnlyShapeInfo(op.x(), dimension);

        Pointer hostTadShapeInfo = tadBuffers.getFirst().addressPointer();

        DataBuffer offsets = tadBuffers.getSecond();
        Pointer hostTadOffsets = offsets == null ? null : offsets.addressPointer();

        PointerPointer dummy = extraz.get().put(hostTadShapeInfo, hostTadOffsets);

        long st = profilingHookIn(op, tadBuffers.getFirst());

        Pointer x = op.x().data().addressPointer();
        Pointer z = op.z().data().addressPointer();

        if (op.x().data().dataType() == DataBuffer.Type.DOUBLE) {
            if (op.z().isScalar()) {
                int res = (int) loop.execIndexReduceScalarDouble(dummy, op.opNum(),
                                (DoublePointer) op.x().data().addressPointer(),
                                (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                (DoublePointer) getPointerForExtraArgs(op));


                op.setFinalResult(res);
                op.z().putScalar(0, (float) res);
            } else {
                loop.execIndexReduceDouble(dummy, op.opNum(), (DoublePointer) x,
                                (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                (DoublePointer) getPointerForExtraArgs(op), (DoublePointer) z,
                                (IntPointer) op.z().shapeInfoDataBuffer().addressPointer(),
                                (IntPointer) dimensionAddress, dimension.length);
            }

        } else {
            if (op.z().isScalar()) {
                int res = (int) loop.execIndexReduceScalarFloat(dummy, op.opNum(),
                                (FloatPointer) op.x().data().addressPointer(),
                                (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                (FloatPointer) getPointerForExtraArgs(op));

                op.setFinalResult(res);
                op.z().putScalar(0, (float) res);
            } else {
                loop.execIndexReduceFloat(dummy, op.opNum(), (FloatPointer) op.x().data().addressPointer(),
                                (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                (FloatPointer) getPointerForExtraArgs(op),
                                (FloatPointer) op.z().data().addressPointer(),
                                (IntPointer) op.z().shapeInfoDataBuffer().addressPointer(),
                                (IntPointer) dimensionAddress, dimension.length);
            }

        }
        profilingHookOut(op, st);
        return op.z();
    }



    @Override
    public INDArray exec(Accumulation op, int... dimension) {
        Arrays.sort(dimension);

        validateDataType(Nd4j.dataType(), op);

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

        for (int i = 0; i < dimension.length; i++)
            if (dimension[i] >= op.x().rank() && dimension[i] != Integer.MAX_VALUE)
                throw new ND4JIllegalStateException("Op target dimension " + Arrays.toString(dimension)
                                + " contains element that higher then rank of op.X: [" + op.x().rank() + "]");

        for (int i = 0; i < dimension.length; i++) {
            if (dimension[i] < 0)
                dimension[i] += op.x().rank();
        }
        //do op along all dimensions
        if (dimension.length == op.x().rank())
            dimension = new int[] {Integer.MAX_VALUE};


        int[] retShape;
        if (Shape.wholeArrayDimension(dimension))
            retShape = new int[] {1, 1};
        else
            retShape = ArrayUtil.removeIndex(op.x().shape(), dimension);
        //ensure vector is proper shape
        if (retShape.length == 1) {
            if (dimension[0] == 0)
                retShape = new int[] {1, retShape[0]};
            else
                retShape = new int[] {retShape[0], 1};
        } else if (retShape.length == 0) {
            retShape = new int[] {1, 1};
        }

        if (op.x().isVector() && op.x().length() == ArrayUtil.prod(retShape) && ArrayUtil.prodLong(retShape) > 1)
            return op.noOp();

        /**
         * This is the result array.
         * We create it only if we hadn't provided it before
         */
        INDArray ret;
        if (op.z() == null || op.z() == op.x()) {

            if (op.x().data().dataType() == DataBuffer.Type.DOUBLE)
                ret = Nd4j.valueArrayOf(retShape, op.zeroDouble());
            else
                ret = Nd4j.valueArrayOf(retShape, op.zeroFloat());

            op.setZ(ret);
        } else {
            // compare length
            if (op.z().lengthLong() != ArrayUtil.prodLong(retShape))
                throw new ND4JIllegalStateException("Shape of target array for reduction [" + Arrays.toString(op.z().shape()) + "] doesn't match expected [" + Arrays.toString(retShape) + "]");

            if (op.x().data().dataType() == DataBuffer.Type.DOUBLE) {
                op.z().assign(op.zeroDouble());
            } else {
                op.z().assign(op.zeroFloat());
            }

            ret = op.z();
        }

        /**
         * Returns the {@link Shape#createShapeInformation(int[], int[], int, int, char)}
         * and the associated offsets for each {@link INDArray#tensorAlongDimension(int, int...)}
         * The first item is the shape information. The second one is the offsets.
         */
        Pair<DataBuffer, DataBuffer> tadBuffers = tadManager.getTADOnlyShapeInfo(op.x(), dimension);
        /**
         * Note that we use addresses in libnd4j.
         * We use reinterpret cast in c to take the long
         * we pass to JNI. This manages overhead.
         */
        Pointer hostTadShapeInfo = tadBuffers.getFirst().addressPointer();

        DataBuffer offsets = tadBuffers.getSecond();
        Pointer hostTadOffsets = offsets == null ? null : offsets.addressPointer();

        /**
         * This is a pointer to a pointer in c.
         */
        PointerPointer dummy = extraz.get().put(hostTadShapeInfo, hostTadOffsets);

        long st = profilingHookIn(op, tadBuffers.getFirst());

        /**
         * Note because dimension arrays don't change,
         * we use an {@link ConstantHandler} which knows how to reserve memory
         * for immutable buffers for the dimensions.
         * This gives us a pointer which is passed around in libnd4j.
         */
        Pointer dimensionAddress = constantHandler.getConstantBuffer(dimension).addressPointer();

        if (op.x().data().dataType() == DataBuffer.Type.DOUBLE) {
            if (op instanceof Variance) {
                if (ret.isScalar()) {
                    ret.putScalar(0, loop.execSummaryStatsScalarDouble(dummy, op.opNum(),
                                    (DoublePointer) op.x().data().addressPointer(),
                                    (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                    (DoublePointer) getPointerForExtraArgs(op), true));
                } else {
                    Variance var = (Variance) op;
                    loop.execSummaryStatsDouble(dummy, op.opNum(), (DoublePointer) op.x().data().addressPointer(),
                                    (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                    (DoublePointer) getPointerForExtraArgs(op),
                                    (DoublePointer) op.z().data().addressPointer(),
                                    (IntPointer) op.z().shapeInfoDataBuffer().addressPointer(),
                                    (IntPointer) dimensionAddress, dimension.length, var.isBiasCorrected());
                }

            }
            //pairwise reduction like similarity of two arrays
            else if (op.y() != null) {
                if (ret.isScalar()) {
                    ret.putScalar(0, loop.execReduce3ScalarDouble(dummy, op.opNum(),
                                    (DoublePointer) op.x().data().addressPointer(),
                                    (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                    (DoublePointer) getPointerForExtraArgs(op),
                                    (DoublePointer) op.y().data().addressPointer(),
                                    (IntPointer) op.y().shapeInfoDataBuffer().addressPointer()));
                } else {
                    loop.execReduce3Double(dummy, op.opNum(), (DoublePointer) op.x().data().addressPointer(),
                                    (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                    (DoublePointer) getPointerForExtraArgs(op),
                                    (DoublePointer) op.y().data().addressPointer(),
                                    (IntPointer) op.y().shapeInfoDataBuffer().addressPointer(),
                                    (DoublePointer) op.z().data().addressPointer(),
                                    (IntPointer) op.z().shapeInfoDataBuffer().addressPointer(),
                                    (IntPointer) dimensionAddress, dimension.length);
                }

            } else {
                if (ret.isScalar()) {
                    ret.putScalar(0, loop.execReduceScalarDouble(dummy, op.opNum(),
                                    (DoublePointer) op.x().data().addressPointer(),
                                    (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                    (DoublePointer) getPointerForExtraArgs(op)));
                } else {
                    loop.execReduceDouble(dummy, op.opNum(), (DoublePointer) op.x().data().addressPointer(),
                                    (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                    (DoublePointer) getPointerForExtraArgs(op),
                                    (DoublePointer) op.z().data().addressPointer(),
                                    (IntPointer) op.z().shapeInfoDataBuffer().addressPointer(),
                                    (IntPointer) dimensionAddress, dimension.length);
                }

            }
        } else {
            if (op instanceof Variance) {
                Variance variance = (Variance) op;
                if (ret.isScalar()) {
                    ret.putScalar(0, loop.execSummaryStatsScalarFloat(dummy, op.opNum(),
                                    (FloatPointer) op.x().data().addressPointer(),
                                    (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                    (FloatPointer) getPointerForExtraArgs(op), variance.isBiasCorrected()));
                } else {
                    loop.execSummaryStatsFloat(dummy, op.opNum(), (FloatPointer) op.x().data().addressPointer(),
                                    (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                    (FloatPointer) getPointerForExtraArgs(op),
                                    (FloatPointer) op.z().data().addressPointer(),
                                    (IntPointer) op.z().shapeInfoDataBuffer().addressPointer(),
                                    (IntPointer) dimensionAddress, dimension.length, variance.isBiasCorrected());
                }

            }

            else if (op.y() != null) {
                if (ret.isScalar()) {
                    ret.putScalar(0, loop.execReduce3ScalarFloat(dummy, op.opNum(),
                                    (FloatPointer) op.x().data().addressPointer(),
                                    (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                    (FloatPointer) getPointerForExtraArgs(op),
                                    (FloatPointer) op.y().data().addressPointer(),
                                    (IntPointer) op.y().shapeInfoDataBuffer().addressPointer()));
                } else {
                    loop.execReduce3Float(dummy, op.opNum(), (FloatPointer) op.x().data().addressPointer(),
                                    (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                    (FloatPointer) getPointerForExtraArgs(op),
                                    (FloatPointer) op.y().data().addressPointer(),
                                    (IntPointer) op.y().shapeInfoDataBuffer().addressPointer(),
                                    (FloatPointer) op.z().data().addressPointer(),
                                    (IntPointer) op.z().shapeInfoDataBuffer().addressPointer(),
                                    (IntPointer) dimensionAddress, dimension.length);
                }

            } else {
                if (ret.isScalar()) {
                    ret.putScalar(0, loop.execReduceScalarFloat(dummy, op.opNum(),
                                    (FloatPointer) op.x().data().addressPointer(),
                                    (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                    (FloatPointer) getPointerForExtraArgs(op)));
                } else {
                    loop.execReduceFloat(dummy, op.opNum(), (FloatPointer) op.x().data().addressPointer(),
                                    (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                    (FloatPointer) getPointerForExtraArgs(op),
                                    (FloatPointer) op.z().data().addressPointer(),
                                    (IntPointer) op.z().shapeInfoDataBuffer().addressPointer(),
                                    (IntPointer) dimensionAddress, dimension.length);
                }

            }
        }

        return ret;
    }

    /**
     * ScalarOp along dimension
     * @param op
     * @param dimension
     */
    private void invoke(ScalarOp op, int[] dimension) {
        Arrays.sort(dimension);
        // do tad magic
        /**
         * Returns the {@link Shape#createShapeInformation(int[], int[], int, int, char)}
         * and the associated offsets for each {@link INDArray#tensorAlongDimension(int, int...)}
         * The first item is the shape information. The second one is the offsets.
         */
        Pair<DataBuffer, DataBuffer> tadBuffers = tadManager.getTADOnlyShapeInfo(op.x(), dimension);

        Pointer hostTadShapeInfo = tadBuffers.getFirst().addressPointer();
        Pointer hostTadOffsets = tadBuffers.getSecond().addressPointer();

        Pointer devTadShapeInfoZ = null;
        Pointer devTadOffsetsZ = null;
        /**
         * Returns the {@link Shape#createShapeInformation(int[], int[], int, int, char)}
         * and the associated offsets for each {@link INDArray#tensorAlongDimension(int, int...)}
         * The first item is the shape information. The second one is the offsets.
         *
         * Note that this is the *result* TAD information. An op is always input (x) and output (z)
         * for result.
         * This is for assigning the result to of the operation along
         * the proper dimension.
         */
        Pair<DataBuffer, DataBuffer> tadBuffersZ = tadManager.getTADOnlyShapeInfo(op.z(), dimension);

        devTadShapeInfoZ = tadBuffersZ.getFirst().addressPointer();
        devTadOffsetsZ = tadBuffersZ.getSecond().addressPointer();

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

        PointerPointer dummy = extraz.get().put(hostTadShapeInfo, hostTadOffsets, devTadShapeInfoZ, devTadOffsetsZ);


        if (op.x().data().dataType() == DataBuffer.Type.FLOAT) {
            loop.execScalarFloat(dummy, op.opNum(), (FloatPointer) op.x().data().addressPointer(),
                            (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                            (FloatPointer) op.z().data().addressPointer(),
                            (IntPointer) op.z().shapeInfoDataBuffer().addressPointer(),
                            (FloatPointer) op.y().data().addressPointer(), (FloatPointer) getPointerForExtraArgs(op),
                            new IntPointer(dimension), dimension.length);
        } else if (op.x().data().dataType() == DataBuffer.Type.DOUBLE) {
            loop.execScalarDouble(dummy, op.opNum(), (DoublePointer) op.x().data().addressPointer(),
                            (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                            (DoublePointer) op.z().data().addressPointer(),
                            (IntPointer) op.z().shapeInfoDataBuffer().addressPointer(),
                            (DoublePointer) op.y().data().addressPointer(), (DoublePointer) getPointerForExtraArgs(op),
                            new IntPointer(dimension), dimension.length);
        }
    }

    private void exec(ScalarOp op) {
        if (op.x() instanceof IComplexNDArray || executionMode() == ExecutionMode.JAVA) {
            super.exec(op);
        } else {
            long st = profilingHookIn(op);

            validateDataType(Nd4j.dataType(), op);

            if (op.x().length() != op.z().length())
                throw new ND4JIllegalStateException("op.X length should be equal to op.Y length: ["
                                + Arrays.toString(op.x().shapeInfoDataBuffer().asInt()) + "] != ["
                                + Arrays.toString(op.z().shapeInfoDataBuffer().asInt()) + "]");

            if (op.getDimension() != null) {
                invoke(op, op.getDimension());
                return;
            }

            if (op.x().data().dataType() == DataBuffer.Type.DOUBLE) {
                if (op.x().elementWiseStride() >= 1 && !op.isExecSpecial() && op.z().elementWiseStride() >= 1
                                && !op.isExecSpecial()) {
                    loop.execScalarDouble(null, op.opNum(), (DoublePointer) op.x().data().addressPointer(),
                                    op.x().elementWiseStride(), (DoublePointer) op.z().data().addressPointer(),
                                    op.z().elementWiseStride(), op.scalar().doubleValue(),
                                    (DoublePointer) getPointerForExtraArgs(op), op.n());
                } else
                    loop.execScalarDouble(null, op.opNum(), (DoublePointer) op.x().data().addressPointer(),
                                    (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                    (DoublePointer) op.z().data().addressPointer(),
                                    (IntPointer) op.z().shapeInfoDataBuffer().addressPointer(),
                                    op.scalar().doubleValue(), (DoublePointer) getPointerForExtraArgs(op));
            } else {
                if (op.x().elementWiseStride() >= 1 && !op.isExecSpecial() && op.z().elementWiseStride() >= 1
                                && !op.isExecSpecial()) {
                    loop.execScalarFloat(null, op.opNum(), (FloatPointer) op.x().data().addressPointer(),
                                    op.x().elementWiseStride(), (FloatPointer) op.z().data().addressPointer(),
                                    op.z().elementWiseStride(), op.scalar().floatValue(),
                                    (FloatPointer) getPointerForExtraArgs(op), op.n());
                } else
                    loop.execScalarFloat(null, op.opNum(), (FloatPointer) op.x().data().addressPointer(),
                                    (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                    (FloatPointer) op.z().data().addressPointer(),
                                    (IntPointer) op.z().shapeInfoDataBuffer().addressPointer(),
                                    op.scalar().floatValue(), (FloatPointer) getPointerForExtraArgs(op));

            }

            profilingHookOut(op, st);
        }
    }

    private Pointer getPointerForExtraArgs(Op op) {
        if (op.extraArgs() != null)
            return op.extraArgsDataBuff().addressPointer();
        return null;
    }

    private void exec(TransformOp op) {
        long st = 0;

        validateDataType(Nd4j.dataType(), op);

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

        PointerPointer dummy = extraz.get();

        /**
         * This is the {@link org.nd4j.linalg.api.ops.impl.transforms.IsMax}
         * operation.
         *
         * @see {@link Op#extraArgs()}
         * for what an extra argument is in an op.
         *
         * The extra argument in the op here is the {@link org.nd4j.linalg.api.ops.impl.transforms.IsMax#IsMax(INDArray, int...)}
         * dimension to do the ismax along
         */
        if (op.opNum() == 41 && op.extraArgs() != null) {
            int[] dimension = new int[(int) op.extraArgs()[0]];

            for (int i = 0; i < dimension.length; i++) {
                dimension[i] = (int) op.extraArgs()[i + 1];
            }


            /**
             * Returns the {@link Shape#createShapeInformation(int[], int[], int, int, char)}
             * and the associated offsets for each {@link INDArray#tensorAlongDimension(int, int...)}
             * The first item is the shape information. The second one is the offsets.
             */
            Pair<DataBuffer, DataBuffer> tadBuffers = tadManager.getTADOnlyShapeInfo(op.z(), dimension);


            Pointer tad = tadBuffers.getFirst().addressPointer();

            DataBuffer offsets = tadBuffers.getSecond();
            Pointer off = offsets == null ? null : offsets.addressPointer();
            dummy.put(0, tad);
            dummy.put(1, off);

            st = profilingHookIn(op, tadBuffers.getFirst());
        } else
            st = profilingHookIn(op);

        if (op.x().data().dataType() == DataBuffer.Type.DOUBLE) {
            if (op.y() != null) {

                int xEWS = op.x().elementWiseStride();
                int yEWS = op.y().elementWiseStride();
                int zEWS = op.z().elementWiseStride();

                boolean xRow = op.x().isRowVector();
                boolean yRow = op.y().isRowVector();
                boolean zRow = op.z().isRowVector();

                if ((xEWS >= 1 && yEWS >= 1
                                && xEWS == yEWS && !op.isExecSpecial()
                                && op.x().ordering() == op.y().ordering() && op.x().ordering() == op.z().ordering()) || (xEWS >= 1 && yEWS == xEWS && zEWS == xEWS && xRow && yRow && zRow)) {
                    loop.execPairwiseTransformDouble(dummy, op.opNum(), (DoublePointer) op.x().data().addressPointer(),
                                    xEWS, (DoublePointer) op.y().data().addressPointer(),
                                    yEWS, (DoublePointer) op.z().data().addressPointer(),
                                    zEWS, (DoublePointer) getPointerForExtraArgs(op), op.n());

                } else {
                    loop.execPairwiseTransformDouble(dummy, op.opNum(), (DoublePointer) op.x().data().addressPointer(),
                                    (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                    (DoublePointer) op.y().data().addressPointer(),
                                    (IntPointer) op.y().shapeInfoDataBuffer().addressPointer(),
                                    (DoublePointer) op.z().data().addressPointer(),
                                    (IntPointer) op.z().shapeInfoDataBuffer().addressPointer(),
                                    (DoublePointer) getPointerForExtraArgs(op));
                }

            } else {
                if (op.x().elementWiseStride() >= 1 && !op.isExecSpecial() && !op.isExecSpecial()
                                && op.x().ordering() == op.z().ordering()) {
                    loop.execTransformDouble(dummy, op.opNum(), (DoublePointer) op.x().data().addressPointer(),
                                    op.x().elementWiseStride(), (DoublePointer) op.z().data().addressPointer(),
                                    op.z().elementWiseStride(), (DoublePointer) getPointerForExtraArgs(op), op.n());
                } else {
                    loop.execTransformDouble(dummy, op.opNum(), (DoublePointer) op.x().data().addressPointer(),
                                    (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                    (DoublePointer) op.z().data().addressPointer(),
                                    (IntPointer) op.z().shapeInfoDataBuffer().addressPointer(),
                                    (DoublePointer) getPointerForExtraArgs(op));
                }

            }
        } else {
            if (op.y() != null) {
                int xEWS = op.x().elementWiseStride();
                int yEWS = op.y().elementWiseStride();
                int zEWS = op.z().elementWiseStride();

                boolean xRow = op.x().isRowVector();
                boolean yRow = op.y().isRowVector();
                boolean zRow = op.z().isRowVector();

                if ((xEWS >= 1 && yEWS >= 1
                                && xEWS == yEWS && !op.isExecSpecial()
                                && op.x().ordering() == op.y().ordering()) || (xEWS >= 1 && yEWS == xEWS && zEWS == xEWS && xRow && yRow && zRow)) {
                    loop.execPairwiseTransformFloat(dummy, op.opNum(), (FloatPointer) op.x().data().addressPointer(),
                                    xEWS, (FloatPointer) op.y().data().addressPointer(),
                                    yEWS, (FloatPointer) op.z().data().addressPointer(),
                                    zEWS, (FloatPointer) getPointerForExtraArgs(op), op.n());

                } else {
                    loop.execPairwiseTransformFloat(dummy, op.opNum(), (FloatPointer) op.x().data().addressPointer(),
                                    (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                    (FloatPointer) op.y().data().addressPointer(),
                                    (IntPointer) op.y().shapeInfoDataBuffer().addressPointer(),
                                    (FloatPointer) op.z().data().addressPointer(),
                                    (IntPointer) op.z().shapeInfoDataBuffer().addressPointer(),
                                    (FloatPointer) getPointerForExtraArgs(op));
                }

            } else {
                if (op.x().elementWiseStride() >= 1 && !op.isExecSpecial() && op.x().ordering() == op.z().ordering()) {
                    loop.execTransformFloat(dummy, op.opNum(), (FloatPointer) op.x().data().addressPointer(),
                                    op.x().elementWiseStride(), (FloatPointer) op.z().data().addressPointer(),
                                    op.z().elementWiseStride(), (FloatPointer) getPointerForExtraArgs(op), op.n());
                } else {
                    loop.execTransformFloat(dummy, op.opNum(), (FloatPointer) op.x().data().addressPointer(),
                                    (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                    (FloatPointer) op.z().data().addressPointer(),
                                    (IntPointer) op.z().shapeInfoDataBuffer().addressPointer(),
                                    (FloatPointer) getPointerForExtraArgs(op));
                }

            }
        }

        profilingHookOut(op, st);
    }

    @Override
    public INDArray exec(BroadcastOp op, int... dimension) {
        long st = profilingHookIn(op);
        Arrays.sort(dimension);

        validateDataType(Nd4j.dataType(), op);

        for (int i = 0; i < dimension.length; i++)
            if (dimension[i] >= op.x().rank() && dimension[i] != Integer.MAX_VALUE)
                throw new ND4JIllegalStateException("Op target dimension " + Arrays.toString(dimension)
                                + " contains element that higher then rank of op.X: [" + op.x().rank() + "]");
        /**
         * Returns the {@link Shape#createShapeInformation(int[], int[], int, int, char)}
         * and the associated offsets for each {@link INDArray#tensorAlongDimension(int, int...)}
         * The first item is the shape information. The second one is the offsets.
         */
        Pair<DataBuffer, DataBuffer> tadBuffers = tadManager.getTADOnlyShapeInfo(op.x(), dimension);

        Pointer hostTadShapeInfo = tadBuffers.getFirst().addressPointer();
        Pointer hostTadOffsets = tadBuffers.getSecond().addressPointer();

        Pointer devTadShapeInfoZ = null;
        Pointer devTadOffsetsZ = null;

        //        if (!Arrays.equals(op.x().shape(),op.z().shape()) || !Arrays.equals(op.x().stride(),op.z().stride()) || op.x().ordering() != op.z().ordering()) {
        // that's the place where we're going to have second TAD in place
        Pair<DataBuffer, DataBuffer> tadBuffersZ = tadManager.getTADOnlyShapeInfo(op.z(), dimension);

        devTadShapeInfoZ = tadBuffersZ.getFirst().addressPointer();
        devTadOffsetsZ = tadBuffersZ.getSecond().addressPointer();
        /*
        log.info("Broascast dimension: {}", Arrays.toString(dimension));
        log.info("x shape: {}; x TAD: {}; comp TAD: {}", Arrays.toString(op.x().shapeInfoDataBuffer().asInt()), Arrays.toString(tadBuffers.getFirst().asInt()), Arrays.toString(op.x().tensorAlongDimension(0, dimension).shapeInfoDataBuffer().asInt()));
        log.info("z shape: {}; z TAD: {}", Arrays.toString(op.z().shapeInfoDataBuffer().asInt()), Arrays.toString(tadBuffersZ.getFirst().asInt()));
        log.info("y shape: {}", Arrays.toString(op.y().shapeInfoDataBuffer().asInt()));
        log.info("-------------");
        */

        if (extraz.get() == null)
            extraz.set(new PointerPointer(32));

        PointerPointer dummy = extraz.get().put(hostTadShapeInfo, hostTadOffsets, devTadShapeInfoZ, devTadOffsetsZ);

        Pointer dimensionAddress = constantHandler.getConstantBuffer(dimension).addressPointer();

        if (op.x().data().dataType() == DataBuffer.Type.DOUBLE) {
            loop.execBroadcastDouble(dummy, op.opNum(), (DoublePointer) op.x().data().addressPointer(),
                            (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                            (DoublePointer) op.y().data().addressPointer(),
                            (IntPointer) op.y().shapeInfoDataBuffer().addressPointer(),
                            (DoublePointer) op.z().data().addressPointer(),
                            (IntPointer) op.z().shapeInfoDataBuffer().addressPointer(), (IntPointer) dimensionAddress,
                            dimension.length);
        } else {
            loop.execBroadcastFloat(dummy, op.opNum(), (FloatPointer) op.x().data().addressPointer(),
                            (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                            (FloatPointer) op.y().data().addressPointer(),
                            (IntPointer) op.y().shapeInfoDataBuffer().addressPointer(),
                            (FloatPointer) op.z().data().addressPointer(),
                            (IntPointer) op.z().shapeInfoDataBuffer().addressPointer(), (IntPointer) dimensionAddress,
                            dimension.length);
        }

        return op.z();
    }

    private void exec(IndexAccumulation op) {
        if (op.x() instanceof IComplexNDArray || executionMode() == ExecutionMode.JAVA) {
            super.exec(op);

        } else {
            long st = profilingHookIn(op);

            validateDataType(Nd4j.dataType(), op);


            if (op.x().data().dataType() == DataBuffer.Type.DOUBLE) {
                op.setFinalResult((int) loop.execIndexReduceScalarDouble(null, op.opNum(),
                                (DoublePointer) op.x().data().addressPointer(),
                                (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                (DoublePointer) getPointerForExtraArgs(op)));

            } else {
                op.setFinalResult((int) loop.execIndexReduceScalarFloat(null, op.opNum(),
                                (FloatPointer) op.x().data().addressPointer(),
                                (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                (FloatPointer) getPointerForExtraArgs(op)));
            }
            profilingHookOut(op, st);
        }
    }

    private void exec(Accumulation op) {
        if (op.x() instanceof IComplexNDArray || executionMode() == ExecutionMode.JAVA) {
            super.exec(op);
        } else {
            long st = profilingHookIn(op);

            validateDataType(Nd4j.dataType(), op);


            if (op.x().data().dataType() == DataBuffer.Type.DOUBLE) {
                if (op instanceof Variance) {
                    op.setFinalResult(loop.execSummaryStatsScalarDouble(null, op.opNum(),
                                    (DoublePointer) op.x().data().addressPointer(),
                                    (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                    (DoublePointer) getPointerForExtraArgs(op), true));
                } else if (op.y() != null) {
                    op.setFinalResult(loop.execReduce3ScalarDouble(null, op.opNum(),
                                    (DoublePointer) op.x().data().addressPointer(),
                                    (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                    (DoublePointer) getPointerForExtraArgs(op),
                                    (DoublePointer) op.y().data().addressPointer(),
                                    (IntPointer) op.y().shapeInfoDataBuffer().addressPointer()));
                } else {
                    op.setFinalResult(loop.execReduceScalarDouble(null, op.opNum(),
                                    (DoublePointer) op.x().data().addressPointer(),
                                    (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                    (DoublePointer) getPointerForExtraArgs(op)));
                }
            } else {
                if (op instanceof Variance) {
                    Variance variance = (Variance) op;
                    op.setFinalResult(loop.execSummaryStatsScalarFloat(null, op.opNum(),
                                    (FloatPointer) op.x().data().addressPointer(),
                                    (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                    (FloatPointer) getPointerForExtraArgs(op), variance.isBiasCorrected()));
                } else if (op.y() != null) {
                    op.setFinalResult(loop.execReduce3ScalarFloat(null, op.opNum(),
                                    (FloatPointer) op.x().data().addressPointer(),
                                    (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                    (FloatPointer) getPointerForExtraArgs(op),
                                    (FloatPointer) op.y().data().addressPointer(),
                                    (IntPointer) op.y().shapeInfoDataBuffer().addressPointer()));
                } else {
                    op.setFinalResult(loop.execReduceScalarFloat(null, op.opNum(),
                                    (FloatPointer) op.x().data().addressPointer(),
                                    (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                    (FloatPointer) getPointerForExtraArgs(op)));
                }
            }
            profilingHookOut(op, st);
        }
    }


    protected <T extends Aggregate> Pointer getPointer(Batch<T> batch) {
        if (batchPointers.get() == null)
            batchPointers.set(new HashMap<Integer, Pointer>());

        if (!batchPointers.get().containsKey(batch.opNum())) {
            IntPointer pointer = new IntPointer(batch.getSample().getRequiredBatchMemorySize() / 4);
            batchPointers.get().put(batch.opNum(), pointer);
            return pointer;
        }

        return batchPointers.get().get(batch.opNum());
    }


    /**
     * This method executes previously built batch
     *
     * @param batch
     */
    @Override
    public <T extends Aggregate> void exec(Batch<T> batch) {
        //profilingHookIn(batch);

        IntPointer pointer = (IntPointer) getPointer(batch);

        int maxTypes = 5;

        int maxIntArrays = batch.getSample().maxIntArrays();

        int maxArraySize = batch.getSample().maxIntArraySize();


        int indexPos = maxTypes * Batch.getBatchLimit();
        int intArraysPos = indexPos + (batch.getSample().maxIndexArguments() * Batch.getBatchLimit());
        int realPos = (intArraysPos + (maxIntArrays * maxArraySize * Batch.getBatchLimit()))
                        / (Nd4j.dataType() == DataBuffer.Type.DOUBLE ? 2 : 1);
        int argsPos = (realPos + ((batch.getSample().maxRealArguments() * Batch.getBatchLimit())))
                        / (Nd4j.dataType() == DataBuffer.Type.DOUBLE ? 1 : 2);
        int shapesPos = argsPos + (batch.getSample().maxArguments() * Batch.getBatchLimit());
        for (int i = 0; i < batch.getNumAggregates(); i++) {
            T op = batch.getAggregates().get(i);

            // put num arguments
            int idx = i * maxTypes;
            pointer.put(idx, op.getArguments().size());
            pointer.put(idx + 1, op.getShapes().size());
            pointer.put(idx + 2, op.getIndexingArguments().size());
            pointer.put(idx + 3, op.getRealArguments().size());
            pointer.put(idx + 4, op.getIntArrayArguments().size());


            // putting indexing arguments
            for (int e = 0; e < op.getIndexingArguments().size(); e++) {
                idx = indexPos + i * batch.getSample().maxIndexArguments();
                pointer.put(idx + e, op.getIndexingArguments().get(e));
            }

            // putting intArray values
            int bsize = maxIntArrays * maxArraySize;
            for (int e = 0; e < op.getIntArrayArguments().size(); e++) {
                int step = (i * bsize) + (e * maxArraySize);
                if (op.getIntArrayArguments().get(e) != null)
                    for (int x = 0; x < op.getIntArrayArguments().get(e).length; x++) {
                        idx = intArraysPos + step + x;
                        pointer.put(idx, op.getIntArrayArguments().get(e)[x]);
                    }
            }

            // TODO: variable datatype should be handled here
            // putting real arguments

            if (Nd4j.dataType() == DataBuffer.Type.FLOAT) {
                FloatPointer fPtr = new FloatPointer(pointer);
                for (int e = 0; e < op.getRealArguments().size(); e++) {
                    idx = realPos + i * op.maxRealArguments();
                    fPtr.put(idx + e, op.getRealArguments().get(e).floatValue());
                }
            } else if (Nd4j.dataType() == DataBuffer.Type.DOUBLE) {
                DoublePointer dPtr = new DoublePointer(pointer);
                for (int e = 0; e < op.getRealArguments().size(); e++) {
                    idx = realPos + (i * op.maxRealArguments());
                    dPtr.put(idx + e, op.getRealArguments().get(e).doubleValue());
                }
            }

            if (extraz.get() == null)
                extraz.set(new PointerPointer(32));

            // putting arguments pointers

            PointerPointer ptrPtr = new PointerPointer(pointer);//extraz.get().put(pointer);

            for (int e = 0; e < op.getArguments().size(); e++) {
                idx = argsPos + i * batch.getSample().maxArguments();

                if (op.getArguments().get(e) != null) {
                    ptrPtr.put(idx + e, op.getArguments().get(e).data().addressPointer());
                }
            }


            // putting shape pointers
            for (int e = 0; e < op.getShapes().size(); e++) {
                idx = shapesPos + i * batch.getSample().maxShapes();

                if (op.getShapes().get(e) != null)
                    ptrPtr.put(idx + e, op.getShapes().get(e).addressPointer());
            }
        }

        if (Nd4j.dataType() == DataBuffer.Type.FLOAT) {
            loop.execAggregateBatchFloat(null, batch.getNumAggregates(), batch.opNum(),
                            batch.getSample().maxArguments(), batch.getSample().maxShapes(),
                            batch.getSample().maxIntArrays(), batch.getSample().maxIntArraySize(),
                            batch.getSample().maxIndexArguments(), batch.getSample().maxRealArguments(), pointer);
        } else if (Nd4j.dataType() == DataBuffer.Type.DOUBLE) {
            loop.execAggregateBatchDouble(null, batch.getNumAggregates(), batch.opNum(),
                            batch.getSample().maxArguments(), batch.getSample().maxShapes(),
                            batch.getSample().maxIntArrays(), batch.getSample().maxIntArraySize(),
                            batch.getSample().maxIndexArguments(), batch.getSample().maxRealArguments(), pointer);
        } else {
            throw new UnsupportedOperationException("Half precision isn't supported on CPU");
        }
    }

    /**
     * This method takes arbitrary
     * sized list of {@link Aggregate},
     * and packs them into batches
     * Note here that this is mainly used for random number generation
     * for {@link RandomOp} and things like {@link org.nd4j.linalg.api.rng.distribution.Distribution}
     * @param batch the list of {@link Aggregate} to
     *              execute upon
     */
    @Override
    public void exec(List<Aggregate> batch) {
        if (batch.size() == 0)
            return;

        List<Batch<Aggregate>> batches = Batch.getBatches(batch);
        for (Batch<Aggregate> single : batches) {
            this.exec(single);
        }
    }

    /**
     * This method takes arbitrary
     * sized list of {@link Aggregate},
     * and packs them into batches
     * Note here that this is mainly used for random number generation
     * for {@link RandomOp} and things like {@link org.nd4j.linalg.api.rng.distribution.Distribution}
     * @param op the list of {@link Aggregate} to
     *              execute upon
     */
    @Override
    public void exec(Aggregate op) {
        // long st = profilingHookIn(op);

        if (memoryBlocks.get() == null)
            memoryBlocks.set(new HashMap<Integer, AggregateMemoryBlock>());

        if (memoryBlocks.get().get(op.opNum()) == null)
            memoryBlocks.get().put(op.opNum(), new AggregateMemoryBlock(op));

        AggregateMemoryBlock block = memoryBlocks.get().get(op.opNum());

        int numArguments = op.getArguments().size();
        int numIndexArguments = op.getIndexingArguments().size();
        int numRealArguments = op.getRealArguments().size();
        int numShapes = op.getShapes().size();
        int numIntArrays = op.getIntArrayArguments().size();

        PointerPointer arguments = block.getArgumentsPointer(); //new PointerPointer(numArguments);
        List<IntPointer> pointers = new ArrayList<>();
        PointerPointer intArrays = block.getArraysPointer(); //new PointerPointer(numIntArrays);

        for (int x = 0; x < numArguments; x++) {
            arguments.put(x, op.getArguments().get(x) == null ? null
                            : op.getArguments().get(x).data().addressPointer());
        }

        PointerPointer shapes = block.getShapesPointer(); //new PointerPointer(numShapes);

        for (int x = 0; x < numShapes; x++) {
            if (op.getShapes().get(x).dataType() != DataBuffer.Type.INT)
                throw new RuntimeException("ShapeBuffers should have INT data type");

            shapes.put(x, op.getShapes().get(x) == null ? null : op.getShapes().get(x).addressPointer());
        }

        //int[] indexes = new int[numIndexArguments];
        IntPointer pointer = block.getIndexingPointer();
        for (int x = 0; x < numIndexArguments; x++) {
            pointer.put(x, op.getIndexingArguments().get(x));
        }

        //IntPointer pointer = new IntPointer(indexes);

        double[] reals = new double[numRealArguments];
        for (int x = 0; x < numRealArguments; x++) {
            //reals[x] = op.getRealArguments().get(x).doubleValue();
            if (Nd4j.dataType() == DataBuffer.Type.FLOAT)
                ((FloatPointer) block.getRealArgumentsPointer()).put(x, op.getRealArguments().get(x).floatValue());
            else
                ((DoublePointer) block.getRealArgumentsPointer()).put(x, op.getRealArguments().get(x).doubleValue());
        }

        for (int x = 0; x < numIntArrays; x++) {
            IntPointer intPtr = block.getIntArrays().get(x); //new IntPointer(op.getIntArrayArguments().get(x));
            intPtr.put(op.getIntArrayArguments().get(x), 0, op.getIntArrayArguments().get(x).length);
            intArrays.put(x, intPtr);
            pointers.add(intPtr);
        }

        //INDArray realsBuffer = Nd4j.create(reals);


        if (Nd4j.dataType() == DataBuffer.Type.FLOAT) {
            loop.execAggregateFloat(null, op.opNum(), arguments, numArguments, shapes, numShapes, pointer,
                            numIndexArguments, intArrays, numIntArrays, (FloatPointer) block.getRealArgumentsPointer(),
                            numRealArguments);
        } else if (Nd4j.dataType() == DataBuffer.Type.DOUBLE) {
            loop.execAggregateDouble(null, op.opNum(), arguments, numArguments, shapes, numShapes, pointer,
                            numIndexArguments, intArrays, numIntArrays, (DoublePointer) block.getRealArgumentsPointer(),
                            numRealArguments);
        } else {
            throw new UnsupportedOperationException("Half precision isn't supported on CPU");
        }
    }

    /**
     * This method return set of key/value and
     * key/key/value objects,
     * describing current environment
     *
     * @return
     */
    @Override
    public Properties getEnvironmentInformation() {
        Properties properties = super.getEnvironmentInformation();
        properties.put(Nd4jEnvironment.BACKEND_KEY, "CPU");
        properties.put(Nd4jEnvironment.OMP_THREADS_KEY, loop.ompGetMaxThreads());
        properties.put(Nd4jEnvironment.BLAS_THREADS_KEY, Nd4j.factory().blas().getMaxThreads());
        properties.put(Nd4jEnvironment.BLAS_VENDOR_KEY, (Nd4j.factory().blas()).getBlasVendor().toString());
        properties.put(Nd4jEnvironment.HOST_FREE_MEMORY_KEY, Pointer.maxBytes() - Pointer.totalBytes());

        return properties;
    }

    /**
     * This method executes specified RandomOp using default RNG available via Nd4j.getRandom()
     *
     * @param op
     */
    @Override
    public INDArray exec(RandomOp op) {
        return exec(op, Nd4j.getRandom());
    }

    /**
     * This method executes specific
     * RandomOp against specified RNG
     *
     * @param op
     * @param rng
     */
    @Override
    public INDArray exec(RandomOp op, Random rng) {
        if (rng.getStateBuffer() == null)
            throw new IllegalStateException(
                            "You should use one of NativeRandom classes for NativeOperations execution");

        long st = profilingHookIn(op);

        validateDataType(Nd4j.dataType(), op);

        if (op.x() != null && op.y() != null && op.z() != null) {
            // triple arg call
            if (Nd4j.dataType() == DataBuffer.Type.FLOAT) {
                loop.execRandomFloat(null, op.opNum(), rng.getStatePointer(), // rng state ptr
                                (FloatPointer) op.x().data().addressPointer(),
                                (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                (FloatPointer) op.y().data().addressPointer(),
                                (IntPointer) op.y().shapeInfoDataBuffer().addressPointer(),
                                (FloatPointer) op.z().data().addressPointer(),
                                (IntPointer) op.z().shapeInfoDataBuffer().addressPointer(),
                                (FloatPointer) op.extraArgsDataBuff().addressPointer());
            } else if (Nd4j.dataType() == DataBuffer.Type.DOUBLE) {
                loop.execRandomDouble(null, op.opNum(), rng.getStatePointer(), // rng state ptr
                                (DoublePointer) op.x().data().addressPointer(),
                                (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                (DoublePointer) op.y().data().addressPointer(),
                                (IntPointer) op.y().shapeInfoDataBuffer().addressPointer(),
                                (DoublePointer) op.z().data().addressPointer(),
                                (IntPointer) op.z().shapeInfoDataBuffer().addressPointer(),
                                (DoublePointer) op.extraArgsDataBuff().addressPointer());
            }
        } else if (op.x() != null && op.z() != null) {
            //double arg call
            if (Nd4j.dataType() == DataBuffer.Type.FLOAT) {
                loop.execRandomFloat(null, op.opNum(), rng.getStatePointer(), // rng state ptr
                                (FloatPointer) op.x().data().addressPointer(),
                                (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                (FloatPointer) op.z().data().addressPointer(),
                                (IntPointer) op.z().shapeInfoDataBuffer().addressPointer(),
                                (FloatPointer) op.extraArgsDataBuff().addressPointer());
            } else if (Nd4j.dataType() == DataBuffer.Type.DOUBLE) {
                loop.execRandomDouble(null, op.opNum(), rng.getStatePointer(), // rng state ptr
                                (DoublePointer) op.x().data().addressPointer(),
                                (IntPointer) op.x().shapeInfoDataBuffer().addressPointer(),
                                (DoublePointer) op.z().data().addressPointer(),
                                (IntPointer) op.z().shapeInfoDataBuffer().addressPointer(),
                                (DoublePointer) op.extraArgsDataBuff().addressPointer());
            }

        } else {
            // single arg call

            if (Nd4j.dataType() == DataBuffer.Type.FLOAT) {
                loop.execRandomFloat(null, op.opNum(), rng.getStatePointer(), // rng state ptr
                                (FloatPointer) op.z().data().addressPointer(),
                                (IntPointer) op.z().shapeInfoDataBuffer().addressPointer(),
                                (FloatPointer) op.extraArgsDataBuff().addressPointer());
            } else if (Nd4j.dataType() == DataBuffer.Type.DOUBLE) {
                loop.execRandomDouble(null, op.opNum(), rng.getStatePointer(), // rng state ptr
                                (DoublePointer) op.z().data().addressPointer(),
                                (IntPointer) op.z().shapeInfoDataBuffer().addressPointer(),
                                (DoublePointer) op.extraArgsDataBuff().addressPointer());
            }
        }

        profilingHookOut(op, st);

        return op.z();
    }

    @Override
    public TADManager getTADManager() {
        return tadManager;
    }

    /**
     * This class holds memory chunks required for single specific Aggregate op.
     * Can be used together with ThreadLocal variables
     */
    @Data
    private static class AggregateMemoryBlock {
        private List<IntPointer> intArrays = new ArrayList<>();
        private IntPointer indexingPointer;
        private Pointer realArgumentsPointer;
        private PointerPointer shapesPointer;
        private PointerPointer argumentsPointer;
        private PointerPointer arraysPointer;

        private final int opNum;

        private AggregateMemoryBlock(@NonNull Aggregate op) {

            opNum = op.opNum();

            // creating IntArrays
            for (int i = 0; i < op.maxIntArrays(); i++) {
                intArrays.add(new IntPointer(op.maxIntArraySize()));
            }

            // allocating chunk for IndexingArguments
            indexingPointer = new IntPointer(op.maxIndexArguments());

            // allocating chunk for RealArguments
            realArgumentsPointer = Nd4j.dataType() == DataBuffer.Type.DOUBLE ? new DoublePointer(op.maxRealArguments())
                            : new FloatPointer(op.maxRealArguments());

            // allocating chunk for shapesPointer
            shapesPointer = new PointerPointer(op.maxShapes());

            // allocating chunk for argumentsPointer
            argumentsPointer = new PointerPointer(op.maxArguments());

            // chunk for intArrays
            arraysPointer = new PointerPointer(op.maxIntArrays());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            AggregateMemoryBlock that = (AggregateMemoryBlock) o;

            return opNum == that.opNum;
        }

        @Override
        public int hashCode() {
            return opNum;
        }
    }
}
