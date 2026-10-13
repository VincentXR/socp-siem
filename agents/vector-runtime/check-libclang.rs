//! Fail early if a host Rust build script cannot dynamically load libclang.
use std::ffi::{c_char, c_int, c_void, CStr, CString};

#[link(name = "dl")]
unsafe extern "C" {
    fn dlopen(filename: *const c_char, flags: c_int) -> *mut c_void;
    fn dlerror() -> *const c_char;
    fn dlclose(handle: *mut c_void) -> c_int;
}

fn main() {
    let path = CString::new(std::env::args().nth(1).expect("libclang path required")).unwrap();
    unsafe {
        let handle = dlopen(path.as_ptr(), 2); // RTLD_NOW on the pinned Linux/musl builder.
        if handle.is_null() {
            let error = dlerror();
            assert!(!error.is_null(), "dlopen failed without diagnostics");
            panic!("host cannot load libclang: {}", CStr::from_ptr(error).to_string_lossy());
        }
        assert_eq!(dlclose(handle), 0);
    }
}
