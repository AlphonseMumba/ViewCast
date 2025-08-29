import Foundation
import ReplayKit
import VideoToolbox
import AVFoundation

class ScreenCapture: NSObject, RPScreenRecorderDelegate {
    private var compressionSession: VTCompressionSession?
    var onSpsPps: ((Data, Data) -> Void)?
    var onSample: ((Data, Bool) -> Void)?

    func start(width: Int32 = 1280, height: Int32 = 720, bitrate: Int = 6_000_000, fps: Int32 = 30) {
        RPScreenRecorder.shared().isMicrophoneEnabled = false
        RPScreenRecorder.shared().startCapture(handler: { [weak self] (sample, bufferType, error) in
            guard error == nil else { return }
            if bufferType == .video, let self = self, CMSampleBufferIsValid(sample),
               let imageBuffer = CMSampleBufferGetImageBuffer(sample) {
                self.encode(buffer: imageBuffer, pts: CMSampleBufferGetPresentationTimeStamp(sample))
            }
        }, completionHandler: { err in })
        setupEncoder(width: width, height: height, bitrate: bitrate, fps: fps)
    }

    func stop() {
        RPScreenRecorder.shared().stopCapture(handler: { _ in })
        if let cs = compressionSession {
            VTCompressionSessionInvalidate(cs)
        }
        compressionSession = nil
    }

    private func setupEncoder(width: Int32, height: Int32, bitrate: Int, fps: Int32) {
        VTCompressionSessionCreate(allocator: kCFAllocatorDefault, width: width, height: height, codecType: kCMVideoCodecType_H264, encoderSpecification: nil, imageBufferAttributes: nil, compressedDataAllocator: nil, outputCallback: { (outputCallbackRefCon, sourceFrameRefCon, status, infoFlags, sampleBuffer) in
            let me = Unmanaged<ScreenCapture>.fromOpaque(outputCallbackRefCon!).takeUnretainedValue()
            guard let sb = sampleBuffer, CMSampleBufferDataIsReady(sb) else { return }
            if let attachments = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false) as? [[CFString: Any]],
               let keyFrame = attachments.first?[kCMSampleAttachmentKey_NotSync] as? Bool {
                let isKey = !keyFrame
                if isKey, let fmt = CMSampleBufferGetFormatDescription(sb) {
                    var spsPointer: UnsafePointer<UInt8>?; var spsSize: Int = 0
                    var ppsPointer: UnsafePointer<UInt8>?; var ppsSize: Int = 0
                    CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fmt, parameterSetIndex: 0, parameterSetPointerOut: &spsPointer, parameterSetSizeOut: &spsSize, parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil)
                    CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fmt, parameterSetIndex: 1, parameterSetPointerOut: &ppsPointer, parameterSetSizeOut: &ppsSize, parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil)
                    if let sp = spsPointer, let pp = ppsPointer {
                        me.onSpsPps?(Data(bytes: sp, count: spsSize), Data(bytes: pp, count: ppsSize))
                    }
                }
                if let dataBuffer = CMSampleBufferGetDataBuffer(sb) {
                    var length: Int = 0
                    var dataPointer: UnsafeMutablePointer<Int8>?
                    CMBlockBufferGetDataPointer(dataBuffer, atOffset: 0, lengthAtOffsetOut: nil, totalLengthOut: &length, dataPointerOut: &dataPointer)
                    if let dp = dataPointer {
                        var buffer = Data(bytes: dp, count: length)
                        // Convert AVCC length-prefixed to AnnexB
                        var out = Data()
                        var offset = 0
                        while offset + 4 < buffer.count {
                            let size = Int(CFSwapInt32BigToHost(buffer.withUnsafeBytes { $0.load(fromByteOffset: offset, as: UInt32.self) }))
                            offset += 4
                            out.append([0,0,0,1], count: 4)
                            out.append(buffer[offset..<(offset+size)])
                            offset += size
                        }
                        me.onSample?(out, isKey)
                    }
                }
            }
        }, refcon: UnsafeMutableRawPointer(Unmanaged.passUnretained(self).toOpaque()), compressionSessionOut: &compressionSession)

        guard let cs = compressionSession else { return }
        VTSessionSetProperty(cs, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        VTSessionSetProperty(cs, key: kVTCompressionPropertyKey_AverageBitRate, value: bitrate as CFTypeRef)
        let fpsNum = fps as CFTypeRef
        VTSessionSetProperty(cs, key: kVTCompressionPropertyKey_ExpectedFrameRate, value: fpsNum)
        VTSessionSetProperty(cs, key: kVTCompressionPropertyKey_H264EntropyMode, value: kVTH264EntropyMode_CABAC)
        VTSessionSetProperty(cs, key: kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, value: 2 as CFTypeRef)
        VTCompressionSessionPrepareToEncodeFrames(cs)
    }

    private func encode(buffer: CVImageBuffer, pts: CMTime) {
        guard let cs = compressionSession else { return }
        var flags: VTEncodeInfoFlags = []
        VTCompressionSessionEncodeFrame(cs, imageBuffer: buffer, presentationTimeStamp: pts, duration: .invalid, frameProperties: nil, sourceFrameRefcon: nil, infoFlagsOut: &flags)
    }
}
