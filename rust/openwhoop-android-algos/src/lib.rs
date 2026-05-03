use chrono::{DateTime, NaiveDateTime, Utc};
use jni::JNIEnv;
use jni::objects::JLongArray;
use jni::sys::jdoubleArray;
use openwhoop_algos::{StrainCalculator, StressCalculator};
use openwhoop_codec::ParsedHistoryReading;

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
