use chrono::{DateTime, NaiveDateTime, Utc};
use jni::JNIEnv;
use jni::objects::{JByteArray, JLongArray};
use jni::sys::{jboolean, jbyteArray, jdoubleArray, jint};
use openwhoop_algos::{StrainCalculator, StressCalculator};
use openwhoop_codec::{
    HistoryReading, ParsedHistoryReading, WhoopData, WhoopPacket,
    constants::{MetadataType, PacketType, WhoopGeneration},
};
use std::ptr;

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_openwhoop_android_algos_WhoopAlgosNative_calculateStats(
    env: JNIEnv,
    _class: jni::objects::JClass,
    timestamps_millis: JLongArray,
    bpm_values: JLongArray,
    max_hr: i32,
    resting_hr: i32,
) -> jdoubleArray {
    let fallback = empty_result(&env);
    let timestamps = match read_long_array(&env, &timestamps_millis) {
        Ok(value) => value,
        Err(_) => return fallback,
    };
    let bpm = match read_long_array(&env, &bpm_values) {
        Ok(value) => value,
        Err(_) => return fallback,
    };
    if timestamps.len() != bpm.len() {
        return fallback;
    }

    let readings = timestamps
        .into_iter()
        .zip(bpm)
        .filter_map(|(timestamp, bpm)| to_reading(timestamp, bpm))
        .collect::<Vec<_>>();
    if readings.is_empty() {
        return fallback;
    }

    let average_hr = readings.iter().map(|r| f64::from(r.bpm)).sum::<f64>() / readings.len() as f64;
    let min_hr = readings.iter().map(|r| r.bpm).min().map(f64::from).unwrap_or(f64::NAN);
    let max_sample_hr = readings.iter().map(|r| r.bpm).max().map(f64::from).unwrap_or(f64::NAN);
    let stress = StressCalculator::calculate_stress(&readings)
        .map(|score| score.score)
        .unwrap_or(f64::NAN);
    let strain = normalize_hr(max_hr)
        .zip(normalize_hr(resting_hr))
        .and_then(|(max_hr, resting_hr)| StrainCalculator::new(max_hr, resting_hr).calculate(&readings))
        .map(|score| score.0)
        .unwrap_or(f64::NAN);

    let result = match env.new_double_array(5) {
        Ok(value) => value,
        Err(_) => return fallback,
    };
    let values = [average_hr, min_hr, max_sample_hr, stress, strain];
    if env.set_double_array_region(&result, 0, &values).is_err() {
        return fallback;
    }
    result.into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_openwhoop_android_ble_WhoopCodecNative_toggleRealtimeHr(
    env: JNIEnv,
    _class: jni::objects::JClass,
    sequence: jint,
    enabled: jboolean,
) -> jbyteArray {
    framed_command(&env, WhoopPacket::toggle_realtime_hr(enabled != 0).with_seq(sequence as u8))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_openwhoop_android_ble_WhoopCodecNative_helloHarvard(
    env: JNIEnv,
    _class: jni::objects::JClass,
    sequence: jint,
) -> jbyteArray {
    framed_command(&env, WhoopPacket::hello_harvard().with_seq(sequence as u8))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_openwhoop_android_ble_WhoopCodecNative_setTime(
    env: JNIEnv,
    _class: jni::objects::JClass,
    sequence: jint,
) -> jbyteArray {
    match WhoopPacket::set_time() {
        Ok(packet) => framed_command(&env, packet.with_seq(sequence as u8)),
        Err(_) => ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_openwhoop_android_ble_WhoopCodecNative_getName(
    env: JNIEnv,
    _class: jni::objects::JClass,
    sequence: jint,
) -> jbyteArray {
    framed_command(&env, WhoopPacket::get_name().with_seq(sequence as u8))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_openwhoop_android_ble_WhoopCodecNative_enterHighFreqSync(
    env: JNIEnv,
    _class: jni::objects::JClass,
    sequence: jint,
) -> jbyteArray {
    framed_command(&env, WhoopPacket::enter_high_freq_sync().with_seq(sequence as u8))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_openwhoop_android_ble_WhoopCodecNative_historyStart(
    env: JNIEnv,
    _class: jni::objects::JClass,
    sequence: jint,
) -> jbyteArray {
    framed_command(&env, WhoopPacket::history_start().with_seq(sequence as u8))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_openwhoop_android_ble_WhoopCodecNative_historyEnd(
    env: JNIEnv,
    _class: jni::objects::JClass,
    sequence: jint,
    end_data: JByteArray,
) -> jbyteArray {
    let end_data = match env.convert_byte_array(&end_data) {
        Ok(bytes) => bytes,
        Err(_) => return ptr::null_mut(),
    };
    let Ok(end_data) = <[u8; 8]>::try_from(end_data.as_slice()) else {
        return ptr::null_mut();
    };
    framed_command(&env, WhoopPacket::history_end(end_data).with_seq(sequence as u8))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_openwhoop_android_ble_WhoopCodecNative_historyEndFailure(
    env: JNIEnv,
    _class: jni::objects::JClass,
    sequence: jint,
) -> jbyteArray {
    framed_command(&env, WhoopPacket::history_end_failure().with_seq(sequence as u8))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_openwhoop_android_ble_WhoopCodecNative_frameLength(
    env: JNIEnv,
    _class: jni::objects::JClass,
    frame_start: JByteArray,
) -> jint {
    let bytes = match env.convert_byte_array(&frame_start) {
        Ok(bytes) => bytes,
        Err(_) => return -1,
    };
    if bytes.len() < 4 || bytes[0] != 0xAA {
        return -1;
    }
    let payload_length = u16::from_le_bytes([bytes[1], bytes[2]]) as i32;
    if payload_length < 8 {
        return -1;
    }
    4 + payload_length
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_openwhoop_android_ble_WhoopCodecNative_decodeGen4Frame(
    env: JNIEnv,
    _class: jni::objects::JClass,
    frame: JByteArray,
) -> jbyteArray {
    let frame = match env.convert_byte_array(&frame) {
        Ok(bytes) => bytes,
        Err(_) => return ptr::null_mut(),
    };
    let packet = match WhoopPacket::from_data(frame) {
        Ok(packet) => packet,
        Err(_) => return ptr::null_mut(),
    };
    let packet_type = packet.packet_type;
    let command = packet.cmd;
    let data = match WhoopData::from_packet(packet, WhoopGeneration::Gen4) {
        Ok(data) => data,
        Err(_) => return encode_unknown(&env, packet_type, command),
    };
    encode_data(&env, data)
}

fn read_long_array(env: &JNIEnv, array: &JLongArray) -> jni::errors::Result<Vec<i64>> {
    let len = env.get_array_length(array)?;
    let mut values = vec![0_i64; usize::try_from(len).unwrap_or_default()];
    env.get_long_array_region(array, 0, &mut values)?;
    Ok(values)
}

fn to_reading(timestamp_millis: i64, bpm: i64) -> Option<ParsedHistoryReading> {
    let bpm = u8::try_from(bpm).ok().filter(|value| *value > 0)?;
    let time = DateTime::<Utc>::from_timestamp_millis(timestamp_millis)?.naive_utc();
    Some(ParsedHistoryReading {
        time: NaiveDateTime::new(time.date(), time.time()),
        bpm,
        rr: vec![],
        imu_data: None,
        gravity: None,
    })
}

fn normalize_hr(value: i32) -> Option<u8> {
    u8::try_from(value).ok().filter(|hr| *hr > 0)
}

fn empty_result(env: &JNIEnv) -> jdoubleArray {
    let result = env.new_double_array(5).expect("JVM should allocate stats array");
    let values = [f64::NAN; 5];
    env.set_double_array_region(&result, 0, &values)
        .expect("JVM should write stats array");
    result.into_raw()
}

fn framed_command(env: &JNIEnv, packet: WhoopPacket) -> jbyteArray {
    match packet.framed_packet() {
        Ok(bytes) => byte_array(env, &bytes),
        Err(_) => ptr::null_mut(),
    }
}

fn byte_array(env: &JNIEnv, bytes: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(bytes) {
        Ok(array) => array.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}

fn encode_data(env: &JNIEnv, data: WhoopData) -> jbyteArray {
    match data {
        WhoopData::RealtimeHr { unix, bpm } => encode_hr(env, 0, u64::from(unix), bpm),
        WhoopData::HistoryReading(HistoryReading { unix, bpm, .. }) => encode_hr(env, 1, unix, bpm),
        WhoopData::HistoryMetadata {
            unix,
            end_data,
            cmd,
        } => {
            let mut encoded = Vec::with_capacity(15);
            encoded.push(2);
            encoded.push(metadata_type(cmd));
            encoded.extend_from_slice(&u64::from(unix).to_le_bytes());
            encoded.extend_from_slice(&end_data);
            byte_array(env, &encoded)
        }
        WhoopData::CommandResponse(response) => {
            let encoded = [3, response.cmd, response.origin_seq, response.result];
            byte_array(env, &encoded)
        }
        _ => {
            let encoded = [0];
            byte_array(env, &encoded)
        }
    }
}

fn encode_hr(env: &JNIEnv, source: u8, unix: u64, bpm: u8) -> jbyteArray {
    let mut encoded = Vec::with_capacity(11);
    encoded.push(1);
    encoded.push(source);
    encoded.extend_from_slice(&unix.to_le_bytes());
    encoded.push(bpm);
    byte_array(env, &encoded)
}

fn encode_unknown(env: &JNIEnv, packet_type: PacketType, command: u8) -> jbyteArray {
    let encoded = [0, packet_type.as_u8(), command];
    byte_array(env, &encoded)
}

fn metadata_type(value: MetadataType) -> u8 {
    value.as_u8()
}
