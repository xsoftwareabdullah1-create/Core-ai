use std::ffi::CStr;
use std::os::raw::c_char;
use std::slice;

mod intent_engine;

#[no_mangle]
pub unsafe extern "C" fn ultron_classify_intent(input: *const c_char, output: *mut c_char, capacity: usize) -> usize {
    if input.is_null() || output.is_null() || capacity == 0 { return 0; }
    let raw = CStr::from_ptr(input).to_string_lossy();
    let label = intent_engine::classify_label(&raw);
    let bytes = label.as_bytes();
    let count = bytes.len().min(capacity.saturating_sub(1));
    let out = slice::from_raw_parts_mut(output as *mut u8, capacity);
    out[..count].copy_from_slice(&bytes[..count]);
    out[count] = 0;
    count
}

#[no_mangle]
pub extern "C" fn ultron_engine_version() -> u32 { 2 }
